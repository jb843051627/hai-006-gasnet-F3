package com.fc.v2.controller.admin;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.domain.ResultTable;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.TGasStopApprove;
import com.fc.v2.model.auto.TGasStopBill;
import com.fc.v2.service.ITGasStopBillService;
import com.fc.v2.shiro.util.ShiroUtils;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.*;

/**
 * 燃气管段停用申请签核 Controller（approval-chain 形状：发起 + 四级签批 + 履历查询）
 *
 * @author fuce
 * @date 2026-09-27
 */
@Api(value = "燃气管段停用签核")
@Controller
@RequestMapping("/gasStopBill")
public class GasStopBillController extends BaseController {

    private final String prefix = "admin/gasStopBill";

    @Autowired
    private ITGasStopBillService gasStopBillService;

    @ApiOperation(value = "停用签核台账跳转", notes = "停用签核台账跳转")
    @GetMapping("/view")
    @RequiresPermissions("gasStopBill:view")
    public String view(ModelMap model) {
        return prefix + "/list";
    }

    @Log(title = "燃气管段停用签核台账查询", action = "list")
    @ApiOperation(value = "停用签核台账", notes = "停用签核台账")
    @GetMapping("/list")
    @RequiresPermissions("gasStopBill:list")
    @ResponseBody
    public ResultTable list(TGasStopBill record) {
        QueryWrapper<TGasStopBill> queryWrapper = new QueryWrapper<TGasStopBill>();
        queryWrapper.eq("del_flag", 0);
        if (record.getBillNo() != null && !record.getBillNo().isEmpty()) {
            queryWrapper.like("bill_no", record.getBillNo());
        }
        if (record.getPipeName() != null && !record.getPipeName().isEmpty()) {
            queryWrapper.like("pipe_name", record.getPipeName());
        }
        if (record.getStatus() != null) {
            queryWrapper.eq("status", record.getStatus());
        }
        queryWrapper.orderByDesc("create_time");
        startPage();
        com.github.pagehelper.PageInfo<TGasStopBill> page =
                new com.github.pagehelper.PageInfo<TGasStopBill>(gasStopBillService.selectTGasStopBillList(queryWrapper));
        return pageTable(page.getList(), page.getTotal());
    }

    @ApiOperation(value = "发起停用申请跳转", notes = "发起停用申请跳转")
    @GetMapping("/addView")
    @RequiresPermissions("gasStopBill:apply")
    public String addView(ModelMap model) {
        model.put("pipes", gasStopBillService.listInServicePipes());
        return prefix + "/add";
    }

    @Log(title = "燃气管段停用申请发起", action = "apply")
    @ApiOperation(value = "发起停用申请", notes = "运维部门选择管段并填写理由后发起")
    @PostMapping("/apply")
    @RequiresPermissions("gasStopBill:apply")
    @ResponseBody
    public AjaxResult apply(Long pipeId, String applyReason) {
        gasStopBillService.apply(pipeId, applyReason, ShiroUtils.getLoginName());
        return success("停用申请已发起，进入安全管理部门审核");
    }

    @ApiOperation(value = "停用申请详情履历跳转", notes = "停用申请详情履历跳转")
    @GetMapping("/detailView")
    @RequiresPermissions("gasStopBill:view")
    public String detailView(Long id, ModelMap model) {
        TGasStopBill bill = gasStopBillService.selectTGasStopBillById(id);
        List<TGasStopApprove> approves = bill == null ? null : gasStopBillService.listApprove(id);
        model.put("bill", bill);
        model.put("approves", approves);
        model.put("loginName", ShiroUtils.getLoginName());
        return prefix + "/detail";
    }

    @Log(title = "燃气管段停用签批同意", action = "approve")
    @ApiOperation(value = "签批同意", notes = "当前审核环节同意，管理批准同意后执行管段停用")
    @PostMapping("/approve")
    @RequiresPermissions("gasStopBill:approve")
    @ResponseBody
    public AjaxResult approve(Long id, String comment) {
        TGasStopBill bill = gasStopBillService.approve(id, ShiroUtils.getLoginName(), comment);
        if (bill.getStatus() != null && bill.getStatus() == 1) {
            return success("终审批准，管段已置停用，该管段后续巡检任务已禁止生成");
        }
        return success("已同意，流转至下一审批环节");
    }

    @Log(title = "燃气管段停用申请否决", action = "reject")
    @ApiOperation(value = "否决申请", notes = "否决后流程终止，管段维持原状")
    @PostMapping("/reject")
    @RequiresPermissions("gasStopBill:reject")
    @ResponseBody
    public AjaxResult reject(Long id, String comment) {
        gasStopBillService.reject(id, ShiroUtils.getLoginName(), comment);
        return success("已否决该停用申请");
    }

    @Log(title = "燃气管段停用申请退回", action = "rollback")
    @ApiOperation(value = "退回补充材料", notes = "退回运维部门补充材料后重新提交")
    @PostMapping("/rollback")
    @RequiresPermissions("gasStopBill:rollback")
    @ResponseBody
    public AjaxResult rollback(Long id, String comment) {
        gasStopBillService.rollback(id, ShiroUtils.getLoginName(), comment);
        return success("已退回运维部门补充材料");
    }

    @Log(title = "燃气管段停用申请补充重提", action = "resubmit")
    @ApiOperation(value = "补充材料后重新提交", notes = "运维部门补充材料，单据回到退回它的审核环节")
    @PostMapping("/resubmit")
    @RequiresPermissions("gasStopBill:apply")
    @ResponseBody
    public AjaxResult resubmit(Long id, String comment) {
        gasStopBillService.resubmit(id, ShiroUtils.getLoginName(), comment);
        return success("补充材料已提交，重新进入审批流程");
    }
}
