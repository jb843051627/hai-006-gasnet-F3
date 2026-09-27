package com.fc.v2.service.impl;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.mapper.auto.TGasPipeMapper;
import com.fc.v2.mapper.auto.TGasPlanTaskMapper;
import com.fc.v2.mapper.auto.TGasStopApproveMapper;
import com.fc.v2.mapper.auto.TGasStopBillMapper;
import com.fc.v2.model.auto.TGasPipe;
import com.fc.v2.model.auto.TGasPlanTask;
import com.fc.v2.model.auto.TGasStopApprove;
import com.fc.v2.model.auto.TGasStopBill;
import com.fc.v2.service.ITGasStopBillService;

/**
 * 燃气管段停用申请签核单 Service业务层处理（approval-chain 形状：四级签批 + 履历留痕）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TGasStopBillServiceImpl implements ITGasStopBillService {

    private static final Logger log = LoggerFactory.getLogger(TGasStopBillServiceImpl.class);

    /** 环节：运维部门申请（退回补充后也落在此口） */
    private static final int NODE_APPLY = 0;
    /** 环节：安全管理部门审核（现场核实） */
    private static final int NODE_SAFETY = 1;
    /** 环节：工程技术部门评估（替代方案可行性） */
    private static final int NODE_ENGINEER = 2;
    /** 环节：公司管理层批准 */
    private static final int NODE_MANAGER = 3;
    /** 已终审（单据不再流转） */
    private static final int NODE_FINISHED = 4;

    private static final String[] NODE_NAMES = {
            "运维部门申请", "安全管理部门审核", "工程技术部门评估", "公司管理层批准"
    };

    /** 履历动作：提交申请 */
    private static final int ACTION_SUBMIT = 0;
    /** 履历动作：同意 */
    private static final int ACTION_APPROVE = 1;
    /** 履历动作：退回补充 */
    private static final int ACTION_ROLLBACK = 2;
    /** 履历动作：否决 */
    private static final int ACTION_REJECT = 3;
    /** 履历动作：补充后重新提交 */
    private static final int ACTION_RESUBMIT = 4;

    /** 报批情形：在核 */
    private static final int STATUS_RUNNING = 0;
    /** 报批情形：已核讫（终审通过，管段已停用） */
    private static final int STATUS_PASS = 1;
    /** 报批情形：已否决 */
    private static final int STATUS_VETO = 2;

    /** 底档情形：在用 */
    private static final int PIPE_IN_SERVICE = 0;
    /** 底档情形：已退役/停用 */
    private static final int PIPE_RETIRED = 1;

    /** 巡检任务：待派 */
    private static final int TASK_WAIT = 0;
    /** 巡检任务：派不出去（停用管段的遗留任务压到此态，扫描不再捡取） */
    private static final int TASK_BLOCKED = 2;

    @javax.annotation.Resource
    private TGasStopBillMapper gasStopBillMapper;

    @javax.annotation.Resource
    private TGasStopApproveMapper gasStopApproveMapper;

    @javax.annotation.Resource
    private TGasPipeMapper gasPipeMapper;

    @javax.annotation.Resource
    private TGasPlanTaskMapper gasPlanTaskMapper;

    @Override
    public TGasStopBill selectTGasStopBillById(Long id) {
        return this.gasStopBillMapper.selectById(id);
    }

    @Override
    public List<TGasStopBill> selectTGasStopBillList(QueryWrapper<TGasStopBill> queryWrapper) {
        return this.gasStopBillMapper.selectList(queryWrapper);
    }

    @Override
    public List<TGasStopApprove> listApprove(Long billId) {
        QueryWrapper<TGasStopApprove> qw = new QueryWrapper<TGasStopApprove>();
        qw.eq("bill_id", billId).eq("del_flag", 0)
                .orderByAsc("approve_time", "id");
        return this.gasStopApproveMapper.selectList(qw);
    }

    @Override
    public List<TGasPipe> listInServicePipes() {
        QueryWrapper<TGasPipe> qw = new QueryWrapper<TGasPipe>();
        qw.eq("del_flag", 0).eq("status", PIPE_IN_SERVICE).orderByAsc("id");
        return this.gasPipeMapper.selectList(qw);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TGasStopBill apply(Long pipeId, String reason, String applicant) {
        if (pipeId == null) {
            throw new IllegalArgumentException("请选择要停用的管段");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("停用申请理由不能为空");
        }
        if (applicant == null || applicant.trim().isEmpty()) {
            throw new IllegalArgumentException("申请人缺失，请重新登录后再发起申请");
        }
        TGasPipe pipe = this.gasPipeMapper.selectById(pipeId);
        if (pipe == null || (pipe.getDelFlag() != null && pipe.getDelFlag() == 1)) {
            throw new IllegalArgumentException("所选管段不存在或已删除");
        }
        if (pipe.getStatus() == null || pipe.getStatus() != PIPE_IN_SERVICE) {
            throw new IllegalArgumentException("管段「" + pipe.getSiteName() + "」已停用，无需重复申请");
        }
        // 同一管段只允许一张在核单据，避免重复停用流程并行
        QueryWrapper<TGasStopBill> dup = new QueryWrapper<TGasStopBill>();
        dup.eq("pipe_id", pipeId).eq("status", STATUS_RUNNING).eq("del_flag", 0);
        if (this.gasStopBillMapper.selectCount(dup) > 0) {
            throw new IllegalArgumentException("管段「" + pipe.getSiteName() + "」已有在核的停用申请，请勿重复发起");
        }

        Date now = new Date();
        TGasStopBill bill = new TGasStopBill();
        bill.setPipeId(pipe.getId());
        bill.setPipeNo(pipe.getSiteNo());
        bill.setPipeName(pipe.getSiteName());
        bill.setApplyReason(reason.trim());
        bill.setNodeNo(NODE_SAFETY);
        bill.setSignMode(0);
        bill.setNeedCount(1);
        bill.setSignCount(0);
        bill.setStatus(STATUS_RUNNING);
        bill.setDelFlag(0);
        this.gasStopBillMapper.insert(bill);
        bill.setBillNo(generateBillNo(bill.getId(), now));
        this.gasStopBillMapper.updateById(bill);

        saveRecord(bill, NODE_APPLY, ACTION_SUBMIT, applicant, reason.trim(), now);
        log.info("[gasStopBill] 停用申请 {} 发起，管段 {}（{}），申请人 {}",
                bill.getBillNo(), pipe.getId(), pipe.getSiteName(), applicant);
        return bill;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TGasStopBill approve(Long id, String approver, String comment) {
        TGasStopBill bill = getRunningBill(id);
        requireApprover(approver);
        requireComment(comment, "审批意见");
        int node = currentNode(bill);
        if (node < NODE_SAFETY || node > NODE_MANAGER) {
            throw new IllegalArgumentException("当前环节不允许审批操作");
        }
        Date now = new Date();
        saveRecord(bill, node, ACTION_APPROVE, approver, comment.trim(), now);

        if (node == NODE_MANAGER) {
            // 终审：落单 → 管段停用 → 禁派巡检任务，同一事务内完成
            bill.setNodeNo(NODE_FINISHED);
            bill.setStatus(STATUS_PASS);
            bill.setSignCount(1);
            bill.setFinishTime(now);
            this.gasStopBillMapper.updateById(bill);
            executeDisable(bill);
            log.info("[gasStopBill] 停用申请 {} 经四级审批通过，管段 {} 已停用", bill.getBillNo(), bill.getPipeId());
        } else {
            bill.setNodeNo(node + 1);
            bill.setSignCount(0);
            this.gasStopBillMapper.updateById(bill);
        }
        return bill;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TGasStopBill reject(Long id, String approver, String comment) {
        TGasStopBill bill = getRunningBill(id);
        requireApprover(approver);
        requireComment(comment, "否决原因");
        int node = currentNode(bill);
        if (node < NODE_SAFETY || node > NODE_MANAGER) {
            throw new IllegalArgumentException("当前环节不允许否决操作");
        }
        Date now = new Date();
        saveRecord(bill, node, ACTION_REJECT, approver, comment.trim(), now);
        bill.setStatus(STATUS_VETO);
        bill.setSignCount(0);
        this.gasStopBillMapper.updateById(bill);
        log.info("[gasStopBill] 停用申请 {} 在[{}]口被 {} 否决", bill.getBillNo(), NODE_NAMES[node], approver);
        return bill;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TGasStopBill rollback(Long id, String approver, String comment) {
        TGasStopBill bill = getRunningBill(id);
        requireApprover(approver);
        requireComment(comment, "退回意见");
        int node = currentNode(bill);
        if (node < NODE_SAFETY || node > NODE_MANAGER) {
            throw new IllegalArgumentException("当前环节不允许退回操作");
        }
        Date now = new Date();
        saveRecord(bill, node, ACTION_ROLLBACK, approver, comment.trim(), now);
        bill.setNodeNo(NODE_APPLY);
        bill.setSignCount(0);
        this.gasStopBillMapper.updateById(bill);
        log.info("[gasStopBill] 停用申请 {} 被[{}]口 {} 退回补充材料", bill.getBillNo(), NODE_NAMES[node], approver);
        return bill;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TGasStopBill resubmit(Long id, String applicant, String supplement) {
        TGasStopBill bill = getRunningBill(id);
        requireApprover(applicant);
        requireComment(supplement, "补充说明");
        int node = currentNode(bill);
        if (node != NODE_APPLY) {
            throw new IllegalArgumentException("该单据不在退回补充状态，不能补充后重新提交");
        }
        // 回到退回它的那个审核口；履历缺失时默认回安全审核口
        int backTo = NODE_SAFETY;
        for (TGasStopApprove r : listApprove(id)) {
            if (r.getAction() != null && r.getAction() == ACTION_ROLLBACK) {
                backTo = r.getNodeNo() == null ? NODE_SAFETY : r.getNodeNo();
            }
        }
        Date now = new Date();
        saveRecord(bill, NODE_APPLY, ACTION_RESUBMIT, applicant, supplement.trim(), now);
        bill.setNodeNo(backTo);
        bill.setSignCount(0);
        this.gasStopBillMapper.updateById(bill);
        log.info("[gasStopBill] 停用申请 {} 由 {} 补充材料后重新提交，回到[{}]口",
                bill.getBillNo(), applicant, NODE_NAMES[backTo]);
        return bill;
    }

    /**
     * 终审通过后的执行动作（与单据落库同一事务）：
     * 1. 管段底档置为已停用；
     * 2. 该管段尚未派出的巡检任务压为"派不出去"，扫描派单不再捡取，
     *    从生成侧杜绝停用管段的后续巡检任务。
     */
    private void executeDisable(TGasStopBill bill) {
        TGasPipe pipeUpd = new TGasPipe();
        pipeUpd.setId(bill.getPipeId());
        pipeUpd.setStatus(PIPE_RETIRED);
        int pipeRows = this.gasPipeMapper.updateById(pipeUpd);
        if (pipeRows <= 0) {
            throw new IllegalStateException("管段状态更新失败，pipeId=" + bill.getPipeId());
        }
        UpdateWrapper<TGasPlanTask> uw = new UpdateWrapper<TGasPlanTask>();
        uw.eq("pipe_id", bill.getPipeId()).eq("status", TASK_WAIT).eq("del_flag", 0)
                .set("status", TASK_BLOCKED);
        int blocked = this.gasPlanTaskMapper.update(null, uw);
        if (blocked > 0) {
            log.info("[gasStopBill] 管段 {} 的 {} 条待派巡检任务已压为派不出去", bill.getPipeId(), blocked);
        }
    }

    /** 回查在核单据，不存在/已删除/已终态均拒绝继续签批 */
    private TGasStopBill getRunningBill(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("缺少停用单主键");
        }
        TGasStopBill bill = this.gasStopBillMapper.selectById(id);
        if (bill == null || (bill.getDelFlag() != null && bill.getDelFlag() == 1)) {
            throw new IllegalArgumentException("停用申请不存在");
        }
        if (bill.getStatus() == null || bill.getStatus() != STATUS_RUNNING) {
            throw new IllegalArgumentException("该停用申请已" + (bill.getStatus() != null && bill.getStatus() == STATUS_PASS ? "核讫停用" : "否决") + "，流程已结束");
        }
        return bill;
    }

    private int currentNode(TGasStopBill bill) {
        Integer node = bill.getNodeNo();
        return node == null ? NODE_APPLY : node;
    }

    private void requireApprover(String approver) {
        if (approver == null || approver.trim().isEmpty()) {
            throw new IllegalArgumentException("审批人缺失，请重新登录后再操作");
        }
    }

    private void requireComment(String comment, String field) {
        if (comment == null || comment.trim().isEmpty()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }

    /** 单号：ST + 时间戳 + 单据主键尾段，同库内可追溯且不依赖额外序列 */
    private String generateBillNo(Long id, Date at) {
        String tail = String.valueOf(id);
        if (tail.length() > 6) {
            tail = tail.substring(tail.length() - 6);
        }
        return "ST" + new SimpleDateFormat("yyyyMMddHHmmss").format(at) + tail;
    }

    /** 落一条签批履历：环节、动作、审批人、意见、时间五要素齐备 */
    private void saveRecord(TGasStopBill bill, int node, int action, String operator,
                            String comment, Date at) {
        TGasStopApprove record = new TGasStopApprove();
        record.setBillId(bill.getId());
        record.setBillNo(bill.getBillNo());
        record.setNodeNo(node);
        record.setNodeName(node >= 0 && node < NODE_NAMES.length ? NODE_NAMES[node] : null);
        record.setAction(action);
        record.setApproveBy(operator);
        record.setApproveComment(comment);
        record.setApproveTime(at);
        record.setDelFlag(0);
        this.gasStopApproveMapper.insert(record);
    }
}
