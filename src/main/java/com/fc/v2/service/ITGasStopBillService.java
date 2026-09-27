package com.fc.v2.service;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.model.auto.TGasPipe;
import com.fc.v2.model.auto.TGasStopApprove;
import com.fc.v2.model.auto.TGasStopBill;

/**
 * 燃气管段停用申请签核单 Service接口（approval-chain 形状：运维申请 → 安全审核 → 工程评估 → 管理批准）
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITGasStopBillService {

    /** 按主键回查单据 */
    TGasStopBill selectTGasStopBillById(Long id);

    /** 按条件查询单据台账 */
    List<TGasStopBill> selectTGasStopBillList(QueryWrapper<TGasStopBill> queryWrapper);

    /** 查询单据的全部签批履历（按审批时间正序） */
    List<TGasStopApprove> listApprove(Long billId);

    /** 在用（可发起停用申请）的管段列表，按主键升序 */
    List<TGasPipe> listInServicePipes();

    /**
     * 运维部门发起停用申请。
     *
     * @param pipeId    申请停用管段主键
     * @param reason    停用理由（重大安全隐患/已达设计寿命等）
     * @param applicant 申请人（运维部门经办人）
     * @return 新建的单据（已进入安全管理部门审核口）
     */
    TGasStopBill apply(Long pipeId, String reason, String applicant);

    /**
     * 当前所在口（安全审核/工程评估/管理批准）签署"同意"。
     * 管理批准同意后立即执行：管段置停用、撤销该管段待派巡检任务。
     */
    TGasStopBill approve(Long id, String approver, String comment);

    /** 否决申请：流程终止，管段不动 */
    TGasStopBill reject(Long id, String approver, String comment);

    /** 退回补充材料：单据退回运维部门，由其补充后重新提交 */
    TGasStopBill rollback(Long id, String approver, String comment);

    /**
     * 运维部门补充材料后重新提交，单据回到退回它的审核口。
     *
     * @param supplement 补充说明
     */
    TGasStopBill resubmit(Long id, String applicant, String supplement);
}
