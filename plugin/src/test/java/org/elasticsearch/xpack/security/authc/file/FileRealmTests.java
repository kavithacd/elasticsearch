/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authc.file;

import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.env.TestEnvironment;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.watcher.ResourceWatcherService;
import org.elasticsearch.xpack.security.authc.AuthenticationResult;
import org.elasticsearch.xpack.security.authc.RealmConfig;
import org.elasticsearch.xpack.security.authc.support.Hasher;
import org.elasticsearch.xpack.security.authc.support.UsernamePasswordToken;
import org.elasticsearch.xpack.security.user.User;
import org.junit.Before;
import org.mockito.stubbing.Answer;

import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class FileRealmTests extends ESTestCase {

    private static final Answer<AuthenticationResult> VERIFY_PASSWORD_ANSWER = inv -> {
        assertThat(inv.getArguments().length, is(3));
        Supplier<User> supplier = (Supplier<User>) inv.getArguments()[2];
        return AuthenticationResult.success(supplier.get());
    };

    private FileUserPasswdStore userPasswdStore;
    private FileUserRolesStore userRolesStore;
    private Settings globalSettings;

    @Before
    public void init() throws Exception {
        userPasswdStore = mock(FileUserPasswdStore.class);
        userRolesStore = mock(FileUserRolesStore.class);
        globalSettings = Settings.builder().put("path.home", createTempDir()).build();
    }

    public void testAuthenticate() throws Exception {
        when(userPasswdStore.verifyPassword(eq("user1"), eq(new SecureString("test123")), any(Supplier.class)))
                .thenAnswer(VERIFY_PASSWORD_ANSWER);
        when(userRolesStore.roles("user1")).thenReturn(new String[] { "role1", "role2" });
        RealmConfig config = new RealmConfig("file-test", Settings.EMPTY, globalSettings, TestEnvironment.newEnvironment(globalSettings), new ThreadContext(globalSettings));
        FileRealm realm = new FileRealm(config, userPasswdStore, userRolesStore);
        PlainActionFuture<AuthenticationResult> future = new PlainActionFuture<>();
        realm.authenticate(new UsernamePasswordToken("user1", new SecureString("test123")), future);
        final AuthenticationResult result = future.actionGet();
        assertThat(result.getStatus(), is(AuthenticationResult.Status.SUCCESS));
        User user = result.getUser();
        assertThat(user, notNullValue());
        assertThat(user.principal(), equalTo("user1"));
        assertThat(user.roles(), notNullValue());
        assertThat(user.roles().length, equalTo(2));
        assertThat(user.roles(), arrayContaining("role1", "role2"));
    }

    public void testAuthenticateCaching() throws Exception {
        Settings settings = Settings.builder()
                .put("cache.hash_algo", Hasher.values()[randomIntBetween(0, Hasher.values().length - 1)].name().toLowerCase(Locale.ROOT))
                .build();
        RealmConfig config = new RealmConfig("file-test", settings, globalSettings, TestEnvironment.newEnvironment(globalSettings), new ThreadContext(globalSettings));
        when(userPasswdStore.verifyPassword(eq("user1"), eq(new SecureString("test123")), any(Supplier.class)))
                .thenAnswer(VERIFY_PASSWORD_ANSWER);
        when(userRolesStore.roles("user1")).thenReturn(new String[]{"role1", "role2"});
        FileRealm realm = new FileRealm(config, userPasswdStore, userRolesStore);
        PlainActionFuture<AuthenticationResult> future = new PlainActionFuture<>();
        realm.authenticate(new UsernamePasswordToken("user1", new SecureString("test123")), future);
        User user1 = future.actionGet().getUser();
        future = new PlainActionFuture<>();
        realm.authenticate(new UsernamePasswordToken("user1", new SecureString("test123")), future);
        User user2 = future.actionGet().getUser();
        assertThat(user1, sameInstance(user2));
    }

    public void testAuthenticateCachingRefresh() throws Exception {
        RealmConfig config = new RealmConfig("file-test", Settings.EMPTY, globalSettings, TestEnvironment.newEnvironment(globalSettings), new ThreadContext(globalSettings));
        userPasswdStore = spy(new UserPasswdStore(config));
        userRolesStore = spy(new UserRolesStore(config));
        when(userPasswdStore.verifyPassword(eq("user1"), eq(new SecureString("test123")), any(Supplier.class)))
                .thenAnswer(VERIFY_PASSWORD_ANSWER);
        doReturn(new String[] { "role1", "role2" }).when(userRolesStore).roles("user1");
        FileRealm realm = new FileRealm(config, userPasswdStore, userRolesStore);
        PlainActionFuture<AuthenticationResult> future = new PlainActionFuture<>();
        realm.authenticate(new UsernamePasswordToken("user1", new SecureString("test123")), future);
        User user1 = future.actionGet().getUser();
        future = new PlainActionFuture<>();
        realm.authenticate(new UsernamePasswordToken("user1", new SecureString("test123")), future);
        User user2 = future.actionGet().getUser();
        assertThat(user1, sameInstance(user2));

        userPasswdStore.notifyRefresh();

        future = new PlainActionFuture<>();
        realm.authenticate(new UsernamePasswordToken("user1", new SecureString("test123")), future);
        User user3 = future.actionGet().getUser();
        assertThat(user2, not(sameInstance(user3)));
        future = new PlainActionFuture<>();
        realm.authenticate(new UsernamePasswordToken("user1", new SecureString("test123")), future);
        User user4 = future.actionGet().getUser();
        assertThat(user3, sameInstance(user4));

        userRolesStore.notifyRefresh();

        future = new PlainActionFuture<>();
        realm.authenticate(new UsernamePasswordToken("user1", new SecureString("test123")), future);
        User user5 = future.actionGet().getUser();
        assertThat(user4, not(sameInstance(user5)));
        future = new PlainActionFuture<>();
        realm.authenticate(new UsernamePasswordToken("user1", new SecureString("test123")), future);
        User user6 = future.actionGet().getUser();
        assertThat(user5, sameInstance(user6));
    }

    public void testToken() throws Exception {
        RealmConfig config = new RealmConfig("file-test", Settings.EMPTY, globalSettings, TestEnvironment.newEnvironment(globalSettings), new ThreadContext(globalSettings));
        when(userPasswdStore.verifyPassword(eq("user1"), eq(new SecureString("test123")), any(Supplier.class)))
                .thenAnswer(VERIFY_PASSWORD_ANSWER);
        when(userRolesStore.roles("user1")).thenReturn(new String[]{"role1", "role2"});
        FileRealm realm = new FileRealm(config, userPasswdStore, userRolesStore);

        ThreadContext threadContext = new ThreadContext(Settings.EMPTY);
        UsernamePasswordToken.putTokenHeader(threadContext, new UsernamePasswordToken("user1", new SecureString("test123")));

        UsernamePasswordToken token = realm.token(threadContext);
        assertThat(token, notNullValue());
        assertThat(token.principal(), equalTo("user1"));
        assertThat(token.credentials(), notNullValue());
        assertThat(new String(token.credentials().getChars()), equalTo("test123"));
    }

    public void testLookup() throws Exception {
        when(userPasswdStore.userExists("user1")).thenReturn(true);
        when(userRolesStore.roles("user1")).thenReturn(new String[] { "role1", "role2" });
        RealmConfig config = new RealmConfig("file-test", Settings.EMPTY, globalSettings, TestEnvironment.newEnvironment(globalSettings), new ThreadContext(globalSettings));
        FileRealm realm = new FileRealm(config, userPasswdStore, userRolesStore);

        PlainActionFuture<User> future = new PlainActionFuture<>();
        realm.lookupUser("user1", future);
        User user = future.actionGet();

        assertThat(user, notNullValue());
        assertThat(user.principal(), equalTo("user1"));
        assertThat(user.roles(), notNullValue());
        assertThat(user.roles().length, equalTo(2));
        assertThat(user.roles(), arrayContaining("role1", "role2"));
    }

    public void testLookupCaching() throws Exception {
        when(userPasswdStore.userExists("user1")).thenReturn(true);
        when(userRolesStore.roles("user1")).thenReturn(new String[] { "role1", "role2" });
        RealmConfig config = new RealmConfig("file-test", Settings.EMPTY, globalSettings, TestEnvironment.newEnvironment(globalSettings), new ThreadContext(globalSettings));
        FileRealm realm = new FileRealm(config, userPasswdStore, userRolesStore);

        PlainActionFuture<User> future = new PlainActionFuture<>();
        realm.lookupUser("user1", future);
        User user = future.actionGet();
        future = new PlainActionFuture<>();
        realm.lookupUser("user1", future);
        User user1 = future.actionGet();
        assertThat(user, sameInstance(user1));
        verify(userPasswdStore).userExists("user1");
        verify(userRolesStore).roles("user1");
    }

    public void testLookupCachingWithRefresh() throws Exception {
        RealmConfig config = new RealmConfig("file-test", Settings.EMPTY, globalSettings, TestEnvironment.newEnvironment(globalSettings), new ThreadContext(globalSettings));
        userPasswdStore = spy(new UserPasswdStore(config));
        userRolesStore = spy(new UserRolesStore(config));
        doReturn(true).when(userPasswdStore).userExists("user1");
        doReturn(new String[] { "role1", "role2" }).when(userRolesStore).roles("user1");
        FileRealm realm = new FileRealm(config, userPasswdStore, userRolesStore);
        PlainActionFuture<User> future = new PlainActionFuture<>();
        realm.lookupUser("user1", future);
        User user1 = future.actionGet();
        future = new PlainActionFuture<>();
        realm.lookupUser("user1", future);
        User user2 = future.actionGet();
        assertThat(user1, sameInstance(user2));

        userPasswdStore.notifyRefresh();

        future = new PlainActionFuture<>();
        realm.lookupUser("user1", future);
        User user3 = future.actionGet();
        assertThat(user2, not(sameInstance(user3)));
        future = new PlainActionFuture<>();
        realm.lookupUser("user1", future);
        User user4 = future.actionGet();
        assertThat(user3, sameInstance(user4));

        userRolesStore.notifyRefresh();

        future = new PlainActionFuture<>();
        realm.lookupUser("user1", future);
        User user5 = future.actionGet();
        assertThat(user4, not(sameInstance(user5)));
        future = new PlainActionFuture<>();
        realm.lookupUser("user1", future);
        User user6 = future.actionGet();
        assertThat(user5, sameInstance(user6));
    }

    public void testUsageStats() throws Exception {
        int userCount = randomIntBetween(0, 1000);
        when(userPasswdStore.usersCount()).thenReturn(userCount);

        Settings.Builder settings = Settings.builder();

        int order = randomIntBetween(0, 10);
        settings.put("order", order);

        RealmConfig config = new RealmConfig("file-realm", settings.build(), globalSettings, TestEnvironment.newEnvironment(globalSettings), new ThreadContext(globalSettings));
        FileRealm realm = new FileRealm(config, userPasswdStore, userRolesStore);

        Map<String, Object> usage = realm.usageStats();
        assertThat(usage, is(notNullValue()));
        assertThat(usage, hasEntry("name", "file-realm"));
        assertThat(usage, hasEntry("order", order));
        assertThat(usage, hasEntry("size", userCount));
    }

    static class UserPasswdStore extends FileUserPasswdStore {
        UserPasswdStore(RealmConfig config) {
            super(config, mock(ResourceWatcherService.class));
        }
    }

    static class UserRolesStore extends FileUserRolesStore {
        UserRolesStore(RealmConfig config) {
            super(config, mock(ResourceWatcherService.class));
        }
    }
}
