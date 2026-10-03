package com.evops.config;

import com.evops.common.UserPrincipal;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.credential.SimpleCredentialsMatcher;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 冷链台账内置演示账号：登录主体为 UserPrincipal（携带操作者 ID）。
 * V1~V4 只做认证；V5 起同时提供角色授权——
 * 温控预警模板的维护、预警查看与导出要求 ADMIN 角色（见 ShiroConfig 过滤器链）。
 */
public class ColdChainRealm extends AuthorizingRealm {

    /** username -> (id, password) */
    private static final Map<String, long[]> IDS = new LinkedHashMap<>();
    private static final Map<String, String> PASSWORDS = new LinkedHashMap<>();
    /** username -> 角色集合 */
    private static final Map<String, Set<String>> ROLES = new LinkedHashMap<>();

    static {
        put("bootstrap", 1L, "bootstrap", new String[]{"ADMIN"});
        put("dispatcher", 2L, "dispatcher123", new String[0]);   // 调度/派车
        put("carrier", 3L, "carrier123", new String[0]);         // 承运/在途上报
        put("receiver", 4L, "receiver123", new String[0]);       // 收货签收
        put("admin", 5L, "admin123", new String[]{"ADMIN"});     // 管理员：模板维护/预警查看导出
    }

    private static void put(String username, long id, String password, String[] roles) {
        IDS.put(username, new long[]{id});
        PASSWORDS.put(username, password);
        Set<String> roleSet = new HashSet<>();
        for (String role : roles) {
            roleSet.add(role);
        }
        ROLES.put(username, roleSet);
    }

    public ColdChainRealm() {
        // SimpleCredentialsMatcher 会把 HTTP Basic 提交的 char[] 密码转成字符串后比对
        setCredentialsMatcher(new SimpleCredentialsMatcher());
    }

    @Override
    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
        UserPrincipal principal = (UserPrincipal) principals.getPrimaryPrincipal();
        SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();
        Set<String> roles = ROLES.get(principal.getUsername());
        if (roles != null) {
            info.setRoles(roles);
        }
        return info;
    }

    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
        String username = token.getPrincipal() == null ? null : String.valueOf(token.getPrincipal());
        if (username == null || !PASSWORDS.containsKey(username)) {
            return null;
        }
        UserPrincipal principal = new UserPrincipal(IDS.get(username)[0], username);
        // 凭证由 SimpleCredentialsMatcher 与 token 中的 char[] 密码比对
        return new SimpleAuthenticationInfo(principal, PASSWORDS.get(username), getName());
    }
}
