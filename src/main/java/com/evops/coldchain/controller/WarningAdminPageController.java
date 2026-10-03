package com.evops.coldchain.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 温控预警模板与温度预警管理页面（V5，仅 ADMIN）。
 * 页面路径经 Shiro 过滤器链强制 authcBasic + adminRoleFilter；
 * 页面内 fetch 调用的 /api/cold-chain/admin/** 同样受管理员角色保护。
 */
@Controller
@RequestMapping("/ui/cold-chain")
public class WarningAdminPageController {

    @GetMapping("/warning-admin")
    public String warningAdmin() {
        return "coldchain/warning-admin";
    }
}
