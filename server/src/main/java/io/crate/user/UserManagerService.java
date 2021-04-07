/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.user;

import static io.crate.user.User.CRATE_USER;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;

import io.crate.action.FutureActionListener;
import io.crate.action.sql.SessionContext;
import io.crate.auth.AccessControl;
import io.crate.auth.AccessControlImpl;
import io.crate.exceptions.UserAlreadyExistsException;
import io.crate.exceptions.UserUnknownException;
import io.crate.execution.engine.collect.sources.SysTableRegistry;
import io.crate.metadata.cluster.DDLClusterStateService;
import io.crate.user.metadata.SysPrivilegesTableInfo;
import io.crate.user.metadata.SysUsersTableInfo;
import io.crate.user.metadata.UsersMetadata;
import io.crate.user.metadata.UsersPrivilegesMetadata;

@Singleton
public class UserManagerService implements UserManager, ClusterStateListener {

    private static final Consumer<User> ENSURE_DROP_USER_NOT_SUPERUSER = user -> {
        if (user != null && user.isSuperUser()) {
            throw new UnsupportedOperationException(String.format(
                Locale.ENGLISH, "Cannot drop a superuser '%s'", user.name()));
        }
    };

    private static final Consumer<User> ENSURE_PRIVILEGE_USER_NOT_SUPERUSER = user -> {
        if (user != null && user.isSuperUser()) {
            throw new UnsupportedOperationException(String.format(
                Locale.ENGLISH, "Cannot alter privileges for superuser '%s'", user.name()));
        }
    };

    private static final UserManagerDDLModifier DDL_MODIFIER = new UserManagerDDLModifier();

    private final TransportCreateUserAction transportCreateUserAction;
    private final TransportDropUserAction transportDropUserAction;
    private final TransportAlterUserAction transportAlterUserAction;
    private final TransportPrivilegesAction transportPrivilegesAction;
    private volatile Set<User> users = Set.of(CRATE_USER);

    @Inject
    public UserManagerService(TransportCreateUserAction transportCreateUserAction,
                              TransportDropUserAction transportDropUserAction,
                              TransportAlterUserAction transportAlterUserAction,
                              TransportPrivilegesAction transportPrivilegesAction,
                              SysTableRegistry sysTableRegistry,
                              ClusterService clusterService,
                              DDLClusterStateService ddlClusterStateService) {
        this.transportCreateUserAction = transportCreateUserAction;
        this.transportDropUserAction = transportDropUserAction;
        this.transportAlterUserAction = transportAlterUserAction;
        this.transportPrivilegesAction = transportPrivilegesAction;
        clusterService.addListener(this);
        var userTable = SysUsersTableInfo.create();
        sysTableRegistry.registerSysTable(
            userTable,
            () -> CompletableFuture.completedFuture(users()),
            userTable.expressions(),
            false
        );

        var privilegesTable = SysPrivilegesTableInfo.create();
        sysTableRegistry.registerSysTable(
            privilegesTable,
            () -> CompletableFuture.completedFuture(SysPrivilegesTableInfo.buildPrivilegesRows(users())),
            privilegesTable.expressions(),
            false
        );

        ddlClusterStateService.addModifier(DDL_MODIFIER);
    }

    static Set<User> getUsers(@Nullable UsersMetadata metadata,
                              @Nullable UsersPrivilegesMetadata privilegesMetadata) {
        HashSet<User> users = new HashSet<User>();
        users.add(CRATE_USER);
        if (metadata != null) {
            for (Map.Entry<String, SecureHash> user: metadata.users().entrySet()) {
                String userName = user.getKey();
                SecureHash password = user.getValue();
                Set<Privilege> privileges = null;
                if (privilegesMetadata != null) {
                    privileges = privilegesMetadata.getUserPrivileges(userName);
                    if (privileges == null) {
                        // create empty set
                        privilegesMetadata.createPrivileges(userName, Set.of());
                    }
                }
                users.add(User.of(userName, privileges, password));
            }
        }
        return Collections.unmodifiableSet(users);
    }

    @Override
    public CompletableFuture<Long> createUser(String userName, @Nullable SecureHash hashedPw) {
        FutureActionListener<WriteUserResponse, Long> listener = new FutureActionListener<>(r -> {
            if (r.doesUserExist()) {
                throw new UserAlreadyExistsException(userName);
            }
            return 1L;
        });
        transportCreateUserAction.execute(new CreateUserRequest(userName, hashedPw), listener);
        return listener;
    }

    @Override
    public CompletableFuture<Long> dropUser(String userName, boolean suppressNotFoundError) {
        ENSURE_DROP_USER_NOT_SUPERUSER.accept(findUser(userName));
        FutureActionListener<WriteUserResponse, Long> listener = new FutureActionListener<>(r -> {
            if (r.doesUserExist() == false) {
                if (suppressNotFoundError) {
                    return 0L;
                }
                throw new UserUnknownException(userName);
            }
            return 1L;
        });
        transportDropUserAction.execute(new DropUserRequest(userName, suppressNotFoundError), listener);
        return listener;
    }

    @Override
    public CompletableFuture<Long> alterUser(String userName, @Nullable SecureHash newHashedPw) {
        FutureActionListener<WriteUserResponse, Long> listener = new FutureActionListener<>(r -> {
            if (r.doesUserExist() == false) {
                throw new UserUnknownException(userName);
            }
            return 1L;
        });
        transportAlterUserAction.execute(new AlterUserRequest(userName, newHashedPw), listener);
        return listener;
    }

    @Override
    public CompletableFuture<Long> applyPrivileges(Collection<String> userNames, Collection<Privilege> privileges) {
        userNames.forEach(s -> ENSURE_PRIVILEGE_USER_NOT_SUPERUSER.accept(findUser(s)));
        FutureActionListener<PrivilegesResponse, Long> listener = new FutureActionListener<>(r -> {
            //noinspection PointlessBooleanExpression
            if (r.unknownUserNames().isEmpty() == false) {
                throw new UserUnknownException(r.unknownUserNames());
            }
            return r.affectedRows();
        });
        transportPrivilegesAction.execute(new PrivilegesRequest(userNames, privileges), listener);
        return listener;
    }

    public Iterable<User> users() {
        return users;
    }

    @Override
    public AccessControl getAccessControl(SessionContext sessionContext) {
        return new AccessControlImpl(this, sessionContext);
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        Metadata prevMetadata = event.previousState().metadata();
        Metadata newMetadata = event.state().metadata();

        UsersMetadata prevUsers = prevMetadata.custom(UsersMetadata.TYPE);
        UsersMetadata newUsers = newMetadata.custom(UsersMetadata.TYPE);

        UsersPrivilegesMetadata prevUsersPrivileges = prevMetadata.custom(UsersPrivilegesMetadata.TYPE);
        UsersPrivilegesMetadata newUsersPrivileges = newMetadata.custom(UsersPrivilegesMetadata.TYPE);

        if (prevUsers != newUsers || prevUsersPrivileges != newUsersPrivileges) {
            users = getUsers(newUsers, newUsersPrivileges);
        }
    }


    @Nullable
    public User findUser(String userName) {
        for (User user : users()) {
            if (userName.equals(user.name())) {
                return user;
            }
        }
        return null;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}