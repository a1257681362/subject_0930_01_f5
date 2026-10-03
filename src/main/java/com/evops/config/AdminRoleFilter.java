package com.evops.config;

import org.apache.shiro.subject.Subject;
import org.apache.shiro.web.filter.authz.AuthorizationFilter;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 管理员角色过滤器（V5）。
 * 与 Shiro 内置 roles 过滤器的区别：接口一律走 HTTP Basic，
 * 未认证返回 401（由前置 authcBasic 触发 WWW-Authenticate 质询），
 * 已认证但不具备 ADMIN 角色返回 403，不做登录页重定向。
 *
 * 温控预警模板的维护、温度预警的查看与导出、手工触发扫描全部要求 ADMIN。
 */
public class AdminRoleFilter extends AuthorizationFilter {

    @Override
    protected boolean isAccessAllowed(ServletRequest request, ServletResponse response, Object mappedValue) {
        Subject subject = getSubject(request, response);
        return subject != null && subject.hasRole("ADMIN");
    }

    @Override
    protected boolean onAccessDenied(ServletRequest request, ServletResponse response) {
        HttpServletResponse http = (HttpServletResponse) response;
        try {
            if (getSubject(request, response).getPrincipal() == null) {
                http.sendError(HttpServletResponse.SC_UNAUTHORIZED, "需要管理员身份认证");
            } else {
                http.sendError(HttpServletResponse.SC_FORBIDDEN, "仅管理员可维护温控预警模板、查看或导出温度预警");
            }
        } catch (IOException e) {
            throw new IllegalStateException("写出权限拒绝响应失败", e);
        }
        return false;
    }
}
