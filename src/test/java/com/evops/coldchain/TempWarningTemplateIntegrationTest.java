package com.evops.coldchain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.evops.coldchain.dto.DispatchRequest;
import com.evops.coldchain.dto.DrugCreateRequest;
import com.evops.coldchain.dto.OrderCreateRequest;
import com.evops.coldchain.dto.ProbeBindRequest;
import com.evops.coldchain.dto.ProbeCreateRequest;
import com.evops.coldchain.dto.VehicleCreateRequest;
import com.evops.coldchain.entity.TempWarning;
import com.evops.coldchain.mapper.BatchWarningThresholdSnapMapper;
import com.evops.coldchain.mapper.ExceptionEventMapper;
import com.evops.coldchain.mapper.TempWarningMapper;
import com.evops.coldchain.service.AlertScanService;
import com.evops.coldchain.service.DrugService;
import com.evops.coldchain.service.OrderService;
import com.evops.coldchain.service.ProbeService;
import com.evops.coldchain.service.TransportBatchService;
import com.evops.coldchain.service.VehicleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按药品类别维护温控预警模板（V5）验收测试。
 *
 * 真实 HTTP 入口（TestRestTemplate + Basic 认证，Shiro 完整生效）+ Mapper 直查物理表，覆盖：
 * 1) 类别树阈值沿父链继承、子类在指定温区字段级覆盖、缺字段停点；
 * 2) 派车固化阈值快照：模板变更只影响新建批次，旧批次按创建时快照扫描；
 * 3) 按采样点（批次×探头）计算连续超限时长，短暂抖动按缺口合并，长缺口切断，未达缓冲不出预警；
 * 4) 预警只追加：模板变更/重新扫描不删除已产生预警；
 * 5) 定时任务重启（同一扫描入口重复执行）不多写记录；定时=system、手工=admin，操作者不丢；
 * 6) 管理员查看与导出必须经过 Shiro：匿名 401、非管理员 403、管理员 200；
 * 7) 无类别旧批次仍走 V1 温区越限异常，但不产生类别预警；
 * 8) CSV 导出含操作者并写导出审计流水。
 *
 * 随机端口真实启服务；测试 profile 已把定时扫描间隔拉长，扫描时机由本测试确定触发。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TempWarningTemplateIntegrationTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String BASE = "/api/cold-chain";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private DrugService drugService;
    @Autowired
    private VehicleService vehicleService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private ProbeService probeService;
    @Autowired
    private TransportBatchService batchService;
    @Autowired
    private AlertScanService alertScanService;

    @Autowired
    private TempWarningMapper warningMapper;
    @Autowired
    private BatchWarningThresholdSnapMapper snapMapper;
    @Autowired
    private ExceptionEventMapper exceptionMapper;

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 3, 8, 0);

    @Test
    void categoryTemplateInheritance_snapshotIsolation_jitterMerge_restartIdempotent_andShiroAdminOnly() throws Exception {
        // ================================================================
        // 一、Shiro 权限：匿名 401、调度员 403、管理员 200
        // ================================================================
        ResponseEntity<String> anon = http.getForEntity(adminUrl("/warnings"), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, anon.getStatusCode(), "匿名访问预警列表必须 401");

        ResponseEntity<String> forbidden = http.withBasicAuth("dispatcher", "dispatcher123")
                .getForEntity(adminUrl("/warnings"), String.class);
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode(), "非管理员查看预警必须 403");

        Map<String, Object> tplByCarrier = new LinkedHashMap<>();
        tplByCarrier.put("categoryId", 1);
        ResponseEntity<String> carrierTpl = http.withBasicAuth("carrier", "carrier123")
                .postForEntity(adminUrl("/warning-templates"), jsonEntity(tplByCarrier), String.class);
        assertEquals(HttpStatus.FORBIDDEN, carrierTpl.getStatusCode(), "非管理员维护模板必须 403");

        ResponseEntity<String> dispatcherExport = http.withBasicAuth("dispatcher", "dispatcher123")
                .getForEntity(adminUrl("/warnings/export"), String.class);
        assertEquals(HttpStatus.FORBIDDEN, dispatcherExport.getStatusCode(), "非管理员导出必须 403");

        ResponseEntity<String> dispatcherPage = http.withBasicAuth("dispatcher", "dispatcher123")
                .getForEntity("http://localhost:" + port + "/ui/cold-chain/warning-admin", String.class);
        assertEquals(HttpStatus.FORBIDDEN, dispatcherPage.getStatusCode(), "非管理员访问管理页面必须 403");

        // ================================================================
        // 二、类别树 + 字段级继承/覆盖
        // ================================================================
        // 根类别“生物制品”：完整模板（2~8℃，缓冲30分钟，抖动合并10分钟）
        long rootId = createCategory("生物制品", null);
        maintainTemplate(rootId, new BigDecimal("2"), new BigDecimal("8"), 30, 10,
                "生物制品通用冷链预警基线", "admin");

        // 子类别“疫苗”：只覆盖温区上限为 10℃，下限/缓冲/抖动全部留空 -> 沿父链继承
        long vaccineId = createCategory("疫苗", rootId);
        JsonNode vTpl = maintainTemplate(vaccineId, null, new BigDecimal("10"), null, null,
                "疫苗仅放宽预警上限，其余继承生物制品基线", "admin");
        assertTrue(vTpl.path("success").asBoolean(), vTpl.toString());
        assertEquals(1, vTpl.path("data").path("versionNo").asInt());

        // 维护停点：新建一棵没有任何模板的类别树，子类全留空维护必须被拒绝并说明缺哪些字段
        long emptyRootId = createCategory("中药饮片", null);
        long emptyChildId = createCategory("贵细饮片", emptyRootId);
        JsonNode missing = maintainTemplate(emptyChildId, null, null, null, null, "全空", "admin");
        assertFalse(missing.path("success").asBoolean());
        String missingMsg = missing.path("message").asText();
        assertTrue(missingMsg.contains("预警温区下限"), missingMsg);
        assertTrue(missingMsg.contains("抖动合并缺口分钟"), missingMsg);

        // ================================================================
        // 三、派车固化阈值快照：继承+覆盖合并结果逐字段落库
        // ================================================================
        Long vehicleId = createVehicle("京A·V5001");
        bindValidProbe(vehicleId, "req-v5-a", "SN-V5-A");

        long drugAId = createDrugInCategory("HPV疫苗", vaccineId);
        long batchAId = createDispatchedBatch(drugAId, vehicleId);
        batchService.ship(batchAId);

        com.evops.coldchain.entity.BatchWarningThresholdSnap snapA =
                warningTemplateServiceRequireSnap(batchAId);
        assertEquals(vaccineId, snapA.getCategoryId());
        assertEquals(0, snapA.getTempMinSnap().compareTo(new BigDecimal("2")), "下限应继承根类别 2℃");
        assertEquals(0, snapA.getTempMaxSnap().compareTo(new BigDecimal("10")), "上限应本子类覆盖为 10℃");
        assertEquals(30, snapA.getWarnBufferMinutesSnap(), "缓冲应继承 30 分钟");
        assertEquals(10, snapA.getJitterGapMinutesSnap(), "抖动合并应继承 10 分钟");
        assertEquals(1, snapA.getTemplateVersionNo());
        assertTrue(snapA.getInheritanceDetail().contains("继承"), "来源明细应标注继承字段");
        assertTrue(snapA.getInheritanceDetail().contains("生物制品"));
        assertTrue(snapA.getInheritanceDetail().contains("覆盖"), "来源明细应标注本类覆盖字段");

        // 挂了类别但全树没模板：派车必须被拦截（先有快照才能在途判定）
        long drugNoTplId = createDrugInCategory("野山参粉", emptyChildId);
        JsonNode badDispatch = dispatchHttp(drugNoTplId, vehicleId, "dispatcher");
        assertFalse(badDispatch.path("success").asBoolean());
        assertTrue(badDispatch.path("message").asText().contains("未维护温控预警模板"));

        // ================================================================
        // 四、按采样点连续超限 + 抖动合并（批次A：快照 max=10, buffer=30, jitter=10）
        // ================================================================
        // 段1：05 高 -> 13 正常(缺口8分钟≤抖动窗口) -> 15 高（合并，段起点仍为 t=05）
        //      -> t=35 高，持续 30 分钟达到缓冲 => 1 条超上限预警
        report(batchAId, 0, "5.00");
        report(batchAId, 5, "11.00");
        report(batchAId, 13, "5.00");
        report(batchAId, 15, "11.00");
        report(batchAId, 35, "11.00");
        // 段2：夹正常点的长缺口切断：100 高 -> 120 正常 -> 140 高(距上次超限40分>10，切断)
        //      段2 在 t=150 时仅10分钟，<30，不出预警；直到 t=200（中间无正常采样，稀疏上报视为连续）才达到缓冲
        report(batchAId, 100, "11.00");
        report(batchAId, 120, "5.00");
        report(batchAId, 140, "11.00");
        report(batchAId, 150, "11.00");
        // 段2 在 t=200 触发（150→200 间无正常采样，稀疏上报视为连续）；t=240 同段不重复出
        report(batchAId, 200, "11.00");
        report(batchAId, 240, "11.00");
        // 段4：连续低下限 300(1℃) -> 340(0.5℃)（40分钟）=> 1 条 TEMP_LOW 预警
        report(batchAId, 300, "1.00");
        report(batchAId, 340, "0.50");

        // 由管理员手工触发首扫（模拟管理员手工扫描）：操作者应为 admin
        JsonNode scan1 = adminPost("/warnings/scan", new LinkedHashMap<>(), "admin");
        assertTrue(scan1.path("success").asBoolean(), scan1.toString());
        assertEquals(3, scan1.path("data").path("inserted").asInt(), "抖动合并1条 + 连续超限1条 + 低温1条");
        assertEquals(0, scan1.path("data").path("duplicated").asInt());

        List<TempWarning> warningsA = warningsOf(batchAId);
        assertEquals(3, warningsA.size());
        long highMerged = warningsA.stream()
                .filter(w -> CcConstants.WARNING_HIGH.equals(w.getWarningType())
                        && w.getStartTime().equals(T0.plusMinutes(5))).count();
        assertEquals(1, highMerged, "短暂抖动必须合并到 t=05 起步的同一段");
        TempWarning merged = warningsA.stream()
                .filter(w -> w.getStartTime().equals(T0.plusMinutes(5))).findFirst().get();
        assertEquals(T0.plusMinutes(35), merged.getEndTime());
        assertEquals(30L, merged.getDurationMinutes(), "连续时长按段起点计算（抖动缺口已合并）");
        // 长缺口切断后另起的段：起点必须是 t=140，而不是沿用 t=100
        TempWarning cut = warningsA.stream()
                .filter(w -> CcConstants.WARNING_HIGH.equals(w.getWarningType())
                        && w.getStartTime().equals(T0.plusMinutes(140))).findFirst().orElse(null);
        assertNotNull(cut, "夹正常点且缺口超过抖动窗口必须切断，另起 t=140 新段");
        assertEquals(T0.plusMinutes(200), cut.getEndTime());
        assertEquals(1, warningsA.stream().filter(w -> CcConstants.WARNING_LOW.equals(w.getWarningType())).count());
        warningsA.forEach(w -> {
            assertEquals("admin", w.getOperator(), "手工触发扫描产生的预警操作者必须是登录管理员");
            assertNotNull(w.getCreateBy(), "create_by 操作者ID不能丢");
            assertEquals(1, w.getTemplateVersionNo());
            assertEquals(30, w.getBufferMinutesSnap());
            assertEquals(10, w.getJitterGapMinutesSnap());
        });

        // ================================================================
        // 五、模板维护为新版本：只影响新建批次，批次A 继续按创建时快照
        // ================================================================
        JsonNode v2 = maintainTemplate(vaccineId, null, new BigDecimal("10"), 60, 5,
                "缓冲收紧口径调整为60分钟、抖动窗口5分钟", "admin");
        assertEquals(2, v2.path("data").path("versionNo").asInt());

        // 模板变更后重扫：批次A 已产生的 3 条预警不消失、不新增（仍按 V1 快照，dedup 键不变）
        JsonNode rescanAfterTplChange = adminPost("/warnings/scan", new LinkedHashMap<>(), "admin");
        assertEquals(0, rescanAfterTplChange.path("data").path("inserted").asInt());
        assertEquals(3, warningsOf(batchAId).size(), "模板变更不能让已产生的预警消失");

        // 新批次B 派车：固化 V2 快照（缓冲60、抖动5）
        bindValidProbe(vehicleId, "req-v5-b", "SN-V5-B");
        long drugBId = createDrugInCategory("新冠疫苗", vaccineId);
        long batchBId = createDispatchedBatch(drugBId, vehicleId);
        batchService.ship(batchBId);
        com.evops.coldchain.entity.BatchWarningThresholdSnap snapB =
                warningTemplateServiceRequireSnap(batchBId);
        assertEquals(2, snapB.getTemplateVersionNo());
        assertEquals(60, snapB.getWarnBufferMinutesSnap());
        assertEquals(5, snapB.getJitterGapMinutesSnap());

        // 批次B：40 分钟连续超限在 V2（缓冲60）下不预警；70 分钟才预警
        report(batchBId, 0, "5.00");
        report(batchBId, 10, "11.00");
        report(batchBId, 50, "11.00"); // 连续 40 分钟 < 60，无预警
        // 定时任务通道（服务直调，无 Shiro 主体）：操作者必须落到 system 而不是 null
        AlertScanService.ScanResult scheduled = alertScanService.scanAll();
        assertEquals(0, scheduled.inserted, "40分钟未达V2缓冲60，不应预警");
        report(batchBId, 80, "11.00"); // 距 t=10 连续 70 分钟 >= 60 => 1 条
        AlertScanService.ScanResult scheduled2 = alertScanService.scanAll();
        assertEquals(1, scheduled2.inserted);
        List<TempWarning> warningsB = warningsOf(batchBId);
        assertEquals(1, warningsB.size());
        assertEquals(2, warningsB.get(0).getTemplateVersionNo());
        assertEquals(60, warningsB.get(0).getBufferMinutesSnap());
        assertEquals("system", warningsB.get(0).getOperator(), "定时扫描操作者必须为 system，不能丢");
        assertEquals(0L, warningsB.get(0).getCreateBy());

        // ================================================================
        // 六、重启定时任务：同一扫描入口重复执行，不多写记录
        // ================================================================
        long physicalRowsBefore = warningMapper.selectCount(null);
        AlertScanService.ScanResult restart1 = alertScanService.scanAll();
        AlertScanService.ScanResult restart2 = alertScanService.scanAll();
        JsonNode restartHttp = adminPost("/warnings/scan", new LinkedHashMap<>(), "admin");
        assertEquals(0, restart1.inserted);
        assertEquals(4, restart1.duplicated, "重放命中既有预警，全部去重跳过");
        assertEquals(0, restart2.inserted);
        assertEquals(4, restart2.duplicated);
        assertEquals(0, restartHttp.path("data").path("inserted").asInt());
        assertEquals(4, restartHttp.path("data").path("duplicated").asInt());
        assertEquals(physicalRowsBefore, warningMapper.selectCount(null),
                "重启后重复扫描物理表不得多写一条预警");

        // ================================================================
        // 七、无类别旧批次：仍走 V1 越限自动异常，但不产生类别预警
        // ================================================================
        bindValidProbe(vehicleId, "req-v5-old", "SN-V5-OLD");
        Long oldDrugId = createDrugWithoutCategory("普通输液");
        long oldBatchId = createDispatchedBatch(oldDrugId, vehicleId);
        batchService.ship(oldBatchId);
        report(oldBatchId, 10, "11.50"); // 药品档案温区 2~8，V1 口径立即自动异常
        assertTrue(exceptionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.evops.coldchain.entity.ExceptionEvent>()
                        .eq("batch_id", oldBatchId)).size() >= 1);
        alertScanService.scanAll();
        assertEquals(0, warningsOf(oldBatchId).size(), "无阈值快照的旧批次不产生类别预警");

        // ================================================================
        // 八、管理员查看列表与 CSV 导出（200，含操作者，导出留痕）
        // ================================================================
        ResponseEntity<String> adminList = http.withBasicAuth("admin", "admin123")
                .getForEntity(adminUrl("/warnings"), String.class);
        assertEquals(HttpStatus.OK, adminList.getStatusCode());
        assertTrue(adminList.getBody().contains("TEMP_HIGH"));
        assertTrue(adminList.getBody().contains("TEMP_LOW"));

        long exportLogsBefore = countOpLogs(CcConstants.ACT_WARNING_EXPORT);
        ResponseEntity<String> export = http.withBasicAuth("admin", "admin123")
                .getForEntity(adminUrl("/warnings/export"), String.class);
        assertEquals(HttpStatus.OK, export.getStatusCode());
        MediaType contentType = export.getHeaders().getContentType();
        assertNotNull(contentType);
        assertTrue(contentType.toString().contains("text/csv"));
        String csv = export.getBody();
        assertTrue(csv.contains("预警ID"));
        assertTrue(csv.contains("admin"), "CSV 必须带操作者（手工扫描）");
        assertTrue(csv.contains("system"), "CSV 必须带操作者（定时扫描）");
        assertTrue(csv.contains("TEMP_LOW"));
        long exportLogsAfter = countOpLogs(CcConstants.ACT_WARNING_EXPORT);
        assertEquals(exportLogsBefore + 1, exportLogsAfter, "导出必须逐次写审计流水");

        // 管理页面对管理员放行
        ResponseEntity<String> adminPage = http.withBasicAuth("admin", "admin123")
                .getForEntity("http://localhost:" + port + "/ui/cold-chain/warning-admin", String.class);
        assertEquals(HttpStatus.OK, adminPage.getStatusCode());

        // 批次台账含派车时固化的阈值快照（任意登录角色均可看自己批次台账；预警明细不在这里开放）
        ResponseEntity<String> ledger = http.withBasicAuth("dispatcher", "dispatcher123")
                .getForEntity("http://localhost:" + port + BASE + "/batches/" + batchAId + "/ledger", String.class);
        assertEquals(HttpStatus.OK, ledger.getStatusCode());
        assertTrue(ledger.getBody().contains("warningThresholdSnapshot"));
    }

    // ---------------- helpers ----------------

    private long createCategory(String name, Long parentId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryName", name);
        if (parentId != null) {
            body.put("parentId", parentId);
        }
        JsonNode resp = adminPost("/categories", body, "admin");
        assertTrue(resp.path("success").asBoolean(), resp.toString());
        return resp.path("data").path("id").asLong();
    }

    private JsonNode maintainTemplate(long categoryId, BigDecimal min, BigDecimal max,
                                      Integer buffer, Integer jitter, String reason, String user) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryId", categoryId);
        if (min != null) { body.put("tempMin", min); }
        if (max != null) { body.put("tempMax", max); }
        if (buffer != null) { body.put("warnBufferMinutes", buffer); }
        if (jitter != null) { body.put("jitterGapMinutes", jitter); }
        body.put("changeReason", reason);
        return adminPost("/warning-templates", body, user);
    }

    private long createDrugInCategory(String name, long categoryId) {
        DrugCreateRequest req = new DrugCreateRequest();
        req.setDrugName(name);
        req.setApprovalNo("国药准字V5-" + name);
        req.setTempMin(new BigDecimal("2.00"));
        req.setTempMax(new BigDecimal("8.00"));
        req.setPackageSpec("1支/盒");
        req.setCategoryId(categoryId);
        return drugService.create(req).getId();
    }

    private Long createDrugWithoutCategory(String name) {
        DrugCreateRequest req = new DrugCreateRequest();
        req.setDrugName(name);
        req.setApprovalNo("国药准字V5-OLD-" + name);
        req.setTempMin(new BigDecimal("2.00"));
        req.setTempMax(new BigDecimal("8.00"));
        req.setPackageSpec("1袋/盒");
        return drugService.create(req).getId();
    }

    private Long createVehicle(String plateNo) {
        VehicleCreateRequest req = new VehicleCreateRequest();
        req.setPlateNo(plateNo);
        req.setCarrierName("华冷物流");
        req.setDriverName("张师傅");
        return vehicleService.create(req).getId();
    }

    private void bindValidProbe(Long vehicleId, String requestId, String serialNo) {
        ProbeCreateRequest pc = new ProbeCreateRequest();
        pc.setSerialNo(serialNo);
        pc.setModel("TP-100");
        pc.setRangeMin(new BigDecimal("-40.00"));
        pc.setRangeMax(new BigDecimal("60.00"));
        pc.setCalibrateOrg("华东计量院");
        pc.setValidUntil(T0.plusYears(1));
        pc.setCertNo("CERT-" + serialNo);
        pc.setCalibrateDate(T0.minusDays(1));
        pc.setCertFileName(serialNo + ".txt");
        pc.setCertFileContent("calibration certificate of " + serialNo);
        Long probeId = probeService.createProbe(pc).getId();

        ProbeBindRequest pb = new ProbeBindRequest();
        pb.setRequestId(requestId);
        pb.setVehicleId(vehicleId);
        pb.setProbeId(probeId);
        probeService.bindIdempotent(pb);
    }

    private long createDispatchedBatch(long drugId, Long vehicleId) {
        OrderCreateRequest orderReq = new OrderCreateRequest();
        orderReq.setDrugId(drugId);
        orderReq.setReceiverOrg("V5 验收医院");
        orderReq.setShipQty(10);
        long orderId = orderService.create(orderReq).getId();

        DispatchRequest dispatchReq = new DispatchRequest();
        dispatchReq.setOrderId(orderId);
        dispatchReq.setVehicleId(vehicleId);
        return batchService.dispatch(dispatchReq).getId();
    }

    private JsonNode dispatchHttp(long drugId, Long vehicleId, String user) {
        OrderCreateRequest orderReq = new OrderCreateRequest();
        orderReq.setDrugId(drugId);
        orderReq.setReceiverOrg("V5 验收医院");
        orderReq.setShipQty(10);
        long orderId = orderService.create(orderReq).getId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("vehicleId", vehicleId);
        try {
            return postWithUser("/batches/dispatch", body, user);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void report(long batchId, int minuteOffset, String temperature) {
        com.evops.coldchain.dto.TempReportRequest req = new com.evops.coldchain.dto.TempReportRequest();
        req.setBatchId(batchId);
        req.setRecordTime(T0.plusMinutes(minuteOffset));
        req.setTemperature(new BigDecimal(temperature));
        req.setRecordedBy("车载温控仪");
        batchService.reportTemperature(req);
    }

    private List<TempWarning> warningsOf(long batchId) {
        return warningMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TempWarning>()
                .eq("batch_id", batchId).orderByAsc("id"));
    }

    private com.evops.coldchain.entity.BatchWarningThresholdSnap warningTemplateServiceRequireSnap(long batchId) {
        List<com.evops.coldchain.entity.BatchWarningThresholdSnap> rows =
                snapMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<
                        com.evops.coldchain.entity.BatchWarningThresholdSnap>().eq("batch_id", batchId));
        assertEquals(1, rows.size(), "派车必须固化且只固化一行阈值快照");
        return rows.get(0);
    }

    @Autowired
    private com.evops.coldchain.mapper.OpLogMapper opLogMapper;

    private long countOpLogs(String action) {
        return opLogMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.evops.coldchain.entity.OpLog>()
                        .eq("action", action));
    }

    private JsonNode adminPost(String path, Object body, String user) throws Exception {
        return postWithUser("/admin" + path, body, user);
    }

    private JsonNode postWithUser(String path, Object body, String user) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String password = passwordOf(user);
        String resp = http.withBasicAuth(user, password)
                .postForObject("http://localhost:" + port + BASE + path,
                        new HttpEntity<>(body, headers), String.class);
        return M.readTree(resp);
    }

    private String passwordOf(String user) {
        switch (user) {
            case "admin": return "admin123";
            case "dispatcher": return "dispatcher123";
            case "carrier": return "carrier123";
            case "receiver": return "receiver123";
            default: return user;
        }
    }

    private String adminUrl(String path) {
        return "http://localhost:" + port + BASE + "/admin" + path;
    }

    private HttpEntity<Object> jsonEntity(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
