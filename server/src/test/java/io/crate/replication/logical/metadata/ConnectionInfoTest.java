/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.replication.logical.metadata;

import static io.crate.testing.Asserts.assertThrowsMatches;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESTestCase;
import org.junit.Test;

import io.crate.exceptions.InvalidArgumentException;

public class ConnectionInfoTest extends ESTestCase {

    @Test
    public void test_url_has_valid_prefix() {
        assertThrowsMatches(
            () -> ConnectionInfo.fromURL("postgres:"),
            InvalidArgumentException.class,
            "The connection string must start with \"crate://\" but was: \"postgres:\""
        );
    }

    @Test
    public void test_url_is_not_empty() {
        assertThrowsMatches(
            () -> ConnectionInfo.fromURL(""),
            InvalidArgumentException.class,
            "The connection string must start with \"crate://\" but was: \"\""
        );
    }

    @Test
    public void test_simple_url() {
        var connInfo = ConnectionInfo.fromURL("crate://example.com:1234");
        assertThat(connInfo.hosts(), contains("example.com:1234"));
        assertThat(connInfo.settings(), is(Settings.EMPTY));
    }

    @Test
    public void test_url_without_host() {
        var connInfo = ConnectionInfo.fromURL("crate://");
        assertThat(connInfo.hosts(), contains(":4300"));
        assertThat(connInfo.settings(), is(Settings.EMPTY));
    }

    @Test
    public void test_url_without_port() {
        var connInfo = ConnectionInfo.fromURL("crate://123.123.123.123");
        assertThat(connInfo.hosts(), contains("123.123.123.123:4300"));
        assertThat(connInfo.settings(), is(Settings.EMPTY));
    }

    @Test
    public void test_port_defaults_to_5432_in_pg_tunnel_mode() {
        var connInfo = ConnectionInfo.fromURL("crate://123.123.123.123?mode=pg_tunnel");
        assertThat(connInfo.hosts(), contains("123.123.123.123:5432"));
    }

    @Test
    public void test_multiple_hosts() {
        var connInfo = ConnectionInfo.fromURL("crate://example.com:4310,123.123.123.123");
        assertThat(connInfo.hosts(), contains("example.com:4310","123.123.123.123:4300"));
        assertThat(connInfo.settings(), is(Settings.EMPTY));
    }

    @Test
    public void test_arguments() {
        var connInfo = ConnectionInfo.fromURL("crate://example.com?" +
            "user=my_user&password=1234&" +
            "sslmode=disable"
        );
        assertThat(connInfo.settings(), is(
            Settings.builder()
                .put("user", "my_user")
                .put("password", "1234")
                .put("sslmode", "disable")
                .build()
        ));
    }

    @Test
    public void test_safe_connection_string() {
        var connInfoSniff = ConnectionInfo.fromURL("crate://example.com:4310,123.123.123.123?" +
            "user=my_user&password=1234&" +
            "sslmode=disable"       // <- sslMode is ignored on SNIFF mode
        );
        assertThat(connInfoSniff.safeConnectionString(),
            is("crate://example.com:4310,123.123.123.123:4300?" +
                "user=*&password=*&" +
                "mode=sniff"
            )
        );
        var connInfoPg = ConnectionInfo.fromURL("crate://example.com?" +
            "user=my_user&password=1234&" +
            "mode=pg_tunnel&" +
            "sslmode=disable"
        );
        assertThat(connInfoPg.safeConnectionString(),
            is("crate://example.com:5432?" +
                "user=*&password=*&" +
                "mode=pg_tunnel&" +
                "sslmode=disable"
            )
        );
    }

    @Test
    public void test_invalid_argument() {
        assertThrowsMatches(
            () -> ConnectionInfo.fromURL("crate://?foo=bar"),
            InvalidArgumentException.class,
            "Connection string argument 'foo' is not supported"
        );
    }

    @Test
    public void test_setting_invalid_mode_raises_error_including_valid_options() {
        assertThrowsMatches(
            () -> ConnectionInfo.fromURL("crate://example.com?mode=foo"),
            IllegalArgumentException.class,
            "Invalid connection mode `foo`, supported modes are: `sniff`, `pg_tunnel`"
        );

        assertThrowsMatches(
            () -> ConnectionInfo.fromURL("crate://example.com:5432?mode=foo"),
            IllegalArgumentException.class,
            "Invalid connection mode `foo`, supported modes are: `sniff`, `pg_tunnel`"
        );
    }
}
