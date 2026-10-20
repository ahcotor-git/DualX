package com.dualx.api.exercise;

import com.dualx.api.dto.exercise.Exercise;
import com.dualx.api.dto.exercise.ExerciseLogList;
import com.dualx.api.dto.exercise.Plan;
import com.dualx.api.dto.exercise.PlanPage;
import com.dualx.api.dto.exercise.PlanUpsertRequest;
import com.dualx.api.dto.exercise.Roster;
import com.dualx.api.dto.exercise.StartExerciseRequest;
import com.dualx.api.dto.exercise.StopExerciseResult;
import com.dualx.api.error.NotImplementedException;
import com.dualx.core.domain.ExportFormat;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * exercise 域：想定（plan）/ 编成（roster）/ 当前演习（current）生命周期。
 *
 * <p>「进行中的演习」按契约建模为单例资源 {@code /api/v1/exercise/current}——边缘同一时刻
 * 只允许一场演习，故 GET 读 / POST 起 / DELETE 停。历史场次走 replay 域，本域不暴露 {@code /{exerciseId}}。</p>
 *
 * <p>骨架期只登记路由、参数与响应类型：每个方法体统一抛 {@link NotImplementedException}，
 * 由全局异常出口 {@code ApiExceptionHandler} 转成 501，使「契约已立起」与「业务未实现」在响应上可区分。</p>
 */
@RestController
public class ExerciseController {

    /** 想定列表：分页 + 名称模糊搜索。 */
    @GetMapping("/api/v1/plans")
    public PlanPage listPlans(
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "page", required = false, defaultValue = "1") int page,
            @RequestParam(name = "size", required = false, defaultValue = "20") int size) {
        throw new NotImplementedException("listPlans");
    }

    /** 新建想定。 */
    @PostMapping("/api/v1/plans")
    public Plan createPlan(@RequestBody @Valid PlanUpsertRequest request) {
        throw new NotImplementedException("createPlan");
    }

    /** 下载想定导入模板（xlsx / csv 二进制）。 */
    @GetMapping("/api/v1/plans/template")
    public ResponseEntity<byte[]> downloadPlanTemplate(
            @RequestParam(name = "format", required = false, defaultValue = "XLSX") ExportFormat format) {
        throw new NotImplementedException("downloadPlanTemplate");
    }

    /** 从模板文件导入想定；整文件校验通过才落库。 */
    @PostMapping("/api/v1/plans/import")
    public Plan importPlan(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "name", required = false) String name) {
        throw new NotImplementedException("importPlan");
    }

    /** 想定详情。 */
    @GetMapping("/api/v1/plans/{planId}")
    public Plan getPlan(@PathVariable("planId") String planId) {
        throw new NotImplementedException("getPlan");
    }

    /** 修改想定。 */
    @PutMapping("/api/v1/plans/{planId}")
    public Plan updatePlan(@PathVariable("planId") String planId,
                           @RequestBody @Valid PlanUpsertRequest request) {
        throw new NotImplementedException("updatePlan");
    }

    /** 删除想定。 */
    @DeleteMapping("/api/v1/plans/{planId}")
    public void deletePlan(@PathVariable("planId") String planId) {
        throw new NotImplementedException("deletePlan");
    }

    /** 获取想定编成。 */
    @GetMapping("/api/v1/plans/{planId}/roster")
    public Roster getRoster(@PathVariable("planId") String planId) {
        throw new NotImplementedException("getRoster");
    }

    /** 整体替换想定编成（全量替换语义）。 */
    @PutMapping("/api/v1/plans/{planId}/roster")
    public Roster replaceRoster(@PathVariable("planId") String planId,
                                @RequestBody @Valid Roster request) {
        throw new NotImplementedException("replaceRoster");
    }

    /** 当前演习状态；无演习进行中时返回 running=false。 */
    @GetMapping("/api/v1/exercise/current")
    public Exercise getCurrentExercise() {
        throw new NotImplementedException("getCurrentExercise");
    }

    /** 开始演习。 */
    @PostMapping("/api/v1/exercise/current")
    public Exercise startExercise(@RequestBody @Valid StartExerciseRequest request) {
        throw new NotImplementedException("startExercise");
    }

    /** 结束演习。 */
    @DeleteMapping("/api/v1/exercise/current")
    public StopExerciseResult stopExercise() {
        throw new NotImplementedException("stopExercise");
    }

    /** 当前演习日志流水：按 seq 增量拉取。 */
    @GetMapping("/api/v1/exercise/current/logs")
    public ExerciseLogList listCurrentExerciseLogs(
            @RequestParam(name = "sinceSeq", required = false) Long sinceSeq,
            @RequestParam(name = "limit", required = false, defaultValue = "100") int limit) {
        throw new NotImplementedException("listCurrentExerciseLogs");
    }
}
