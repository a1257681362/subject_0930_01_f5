package com.evops.coldchain.controller;

import com.evops.coldchain.dto.CategoryCreateRequest;
import com.evops.coldchain.dto.TemplateMaintainRequest;
import com.evops.coldchain.entity.DrugCategory;
import com.evops.coldchain.entity.TempWarning;
import com.evops.coldchain.entity.WarningTemplateVersion;
import com.evops.coldchain.service.AlertScanService;
import com.evops.coldchain.service.DrugCategoryService;
import com.evops.coldchain.service.OpLogService;
import com.evops.coldchain.service.WarningTemplateService;
import com.evops.common.ApiResponse;
import com.evops.common.UserContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.evops.coldchain.CcConstants.ACT_WARNING_EXPORT;
import static com.evops.coldchain.CcConstants.ENT_WARNING;

/**
 * 温控预警模板与温度预警管理（V5，仅 ADMIN）。
 * 路径统一在 /api/cold-chain/admin/** 下，Shiro 过滤器链强制 authcBasic + adminRoleFilter：
 * 未认证 401，已登录但非管理员 403——查看与导出都不能绕过权限校验。
 */
@RestController
@RequestMapping("/api/cold-chain/admin")
public class WarningAdminController {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DrugCategoryService categoryService;
    private final WarningTemplateService templateService;
    private final AlertScanService alertScanService;
    private final OpLogService opLogService;

    public WarningAdminController(DrugCategoryService categoryService,
                                  WarningTemplateService templateService,
                                  AlertScanService alertScanService,
                                  OpLogService opLogService) {
        this.categoryService = categoryService;
        this.templateService = templateService;
        this.alertScanService = alertScanService;
        this.opLogService = opLogService;
    }

    // ---------------- 药品类别树 ----------------

    @PostMapping("/categories")
    public ApiResponse<DrugCategory> createCategory(@Valid @RequestBody CategoryCreateRequest req) {
        return ApiResponse.ok(categoryService.create(req));
    }

    @PostMapping("/categories/{id}/revoke")
    public ApiResponse<DrugCategory> revokeCategory(@PathVariable("id") Long id,
                                                    @RequestParam(required = false) String reason) {
        return ApiResponse.ok(categoryService.revoke(id, reason));
    }

    /** 类别树：每个类别附带当前生效模板与逐字段继承解析结果 */
    @GetMapping("/categories")
    public ApiResponse<List<Map<String, Object>>> listCategories() {
        List<Map<String, Object>> views = new ArrayList<>();
        for (DrugCategory category : categoryService.list()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("category", category);
            view.put("path", categoryService.categoryPath(category.getId()));
            WarningTemplateVersion effective = templateService.findEffectiveVersion(category.getId());
            view.put("effectiveVersion", effective);
            view.put("versions", templateService.listVersions(category.getId()));
            // 已配置模板的类别额外展开“沿父链继承+本类覆盖”后的最终阈值与逐字段来源
            if (effective != null) {
                try {
                    WarningTemplateService.ResolvedThresholds resolved =
                            templateService.resolveForDispatch(category.getId());
                    Map<String, Object> resolvedView = new LinkedHashMap<>();
                    resolvedView.put("tempMin", resolved.tempMin);
                    resolvedView.put("tempMax", resolved.tempMax);
                    resolvedView.put("warnBufferMinutes", resolved.warnBufferMinutes);
                    resolvedView.put("jitterGapMinutes", resolved.jitterGapMinutes);
                    resolvedView.put("inheritanceDetail", resolved.inheritanceDetail);
                    view.put("resolved", resolvedView);
                } catch (RuntimeException ignored) {
                    // 父链阈值不完整等配置异常不阻断列表展示，维护时会给出明确停点
                }
            }
            views.add(view);
        }
        return ApiResponse.ok(views);
    }

    // ---------------- 类别预警模板 ----------------

    @PostMapping("/warning-templates")
    public ApiResponse<WarningTemplateVersion> maintainTemplate(@Valid @RequestBody TemplateMaintainRequest req) {
        return ApiResponse.ok(templateService.maintain(req));
    }

    @GetMapping("/warning-templates/{categoryId}/versions")
    public ApiResponse<List<WarningTemplateVersion>> listTemplateVersions(@PathVariable("categoryId") Long categoryId) {
        categoryService.requireCategory(categoryId);
        return ApiResponse.ok(templateService.listVersions(categoryId));
    }

    // ---------------- 温度预警：查询 / 导出 / 手工扫描 ----------------

    @GetMapping("/warnings")
    public ApiResponse<List<TempWarning>> listWarnings(@RequestParam(required = false) Long batchId) {
        return ApiResponse.ok(batchId == null
                ? alertScanService.listAll()
                : alertScanService.listByBatch(batchId));
    }

    /**
     * 手工触发全量扫描（管理员）。与定时任务走同一套确定性重放 + 唯一键去重：
     * 重启后重复执行不多写记录；操作者为当前登录管理员（定时任务为 system）。
     */
    @PostMapping("/warnings/scan")
    public ApiResponse<AlertScanService.ScanResult> scanNow() {
        AlertScanService.ScanResult result = alertScanService.scanAll();
        // 手工触发本身逐次留痕（哪怕无新增），管理员操作者不丢；与定时任务（operator=system）区分
        opLogService.log(ENT_WARNING, null, null,
                com.evops.coldchain.CcConstants.ACT_WARNING_SCAN,
                String.format("管理员 %s 手工触发温控预警扫描：扫描批次=%d，新增预警=%d，重启/并发去重跳过=%d",
                        UserContext.currentName(), result.scannedBatches, result.inserted, result.duplicated));
        return ApiResponse.ok(result);
    }

    /**
     * 导出温度预警 CSV（仅 ADMIN，经 Shiro 过滤器校验后才进入）。
     * 逐次导出写 t_cc_op_log（WARNING_EXPORT），记录管理员账号，导出本身可审计。
     */
    @GetMapping("/warnings/export")
    public ResponseEntity<byte[]> exportCsv(@RequestParam(required = false) Long batchId) {
        List<TempWarning> warnings = batchId == null
                ? alertScanService.listAll()
                : alertScanService.listByBatch(batchId);

        opLogService.log(ENT_WARNING, null, batchId, ACT_WARNING_EXPORT,
                String.format("管理员 %s 导出温度预警 CSV：%s，条数=%d",
                        UserContext.currentName(), batchId == null ? "全部批次" : "批次#" + batchId, warnings.size()));

        byte[] body = buildCsv(warnings).getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"temp-warnings" + (batchId == null ? "" : "-batch-" + batchId) + ".csv\"");
        headers.setContentLength(body.length);
        return new ResponseEntity<>(body, headers, 200);
    }

    private String buildCsv(List<TempWarning> warnings) {
        StringBuilder sb = new StringBuilder("\uFEFF"); // UTF-8 BOM，Excel 直接打开不乱码
        sb.append("预警ID,批次ID,采样点探头ID,预警类型,预警下限℃,预警上限℃,缓冲分钟,抖动合并分钟,")
                .append("模板版本,起始时间,触发时间,连续超限分钟,起始温度℃,触发温度℃,操作者,产生时间,描述\n");
        for (TempWarning w : warnings) {
            sb.append(w.getId()).append(',')
                    .append(w.getBatchId()).append(',')
                    .append(w.getProbeId() == null ? "" : w.getProbeId()).append(',')
                    .append(csv(w.getWarningType())).append(',')
                    .append(w.getThresholdMinSnap()).append(',')
                    .append(w.getThresholdMaxSnap()).append(',')
                    .append(w.getBufferMinutesSnap()).append(',')
                    .append(w.getJitterGapMinutesSnap()).append(',')
                    .append("V").append(w.getTemplateVersionNo()).append(',')
                    .append(w.getStartTime() == null ? "" : w.getStartTime().format(DT)).append(',')
                    .append(w.getEndTime() == null ? "" : w.getEndTime().format(DT)).append(',')
                    .append(w.getDurationMinutes()).append(',')
                    .append(w.getStartTemperature()).append(',')
                    .append(w.getEndTemperature()).append(',')
                    .append(csv(w.getOperator())).append(',')
                    .append(w.getCreateTime() == null ? "" : w.getCreateTime().format(DT)).append(',')
                    .append(csv(w.getDescription())).append('\n');
        }
        return sb.toString();
    }

    /** CSV 字段转义：含逗号/引号/换行时用双引号包裹，内部引号双写 */
    private String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
