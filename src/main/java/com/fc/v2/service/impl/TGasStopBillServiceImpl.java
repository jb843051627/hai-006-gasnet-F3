package com.fc.v2.service.impl;

import org.springframework.stereotype.Service;

import com.fc.v2.mapper.auto.TGasStopBillMapper;
import com.fc.v2.model.auto.TGasStopBill;
import com.fc.v2.service.ITGasStopBillService;

/**
 * 燃气管段停用申请签核单 Service业务层处理（approval-chain 形状：多阶段签批）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TGasStopBillServiceImpl implements ITGasStopBillService {

    private static final int MAX_NODE = 2;
    private static final int MODE_OR = 0;
    private static final int MODE_AND = 1;
    private static final int STATUS_RUNNING = 0;
    private static final int STATUS_PASS = 1;
    private static final int STATUS_VETO = 2;

    @javax.annotation.Resource
    private TGasStopBillMapper gasStopBillMapper;

    @Override
    public TGasStopBill selectTGasStopBillById(Long id) {
        return this.gasStopBillMapper.selectById(id);
    }

    @Override
    public TGasStopBill approve(Long id, String approver, String comment) {
        TGasStopBill r = this.gasStopBillMapper.selectById(id);
        if (r == null || approver == null || approver.trim().isEmpty()) {
            return null;
        }
        r.setNodeNo(Integer.valueOf((r.getNodeNo() == null ? 0 : r.getNodeNo()) + 1));
        r.setStatus(Integer.valueOf(r.getNodeNo() >= MAX_NODE ? STATUS_PASS : STATUS_RUNNING));
        this.gasStopBillMapper.updateById(r);
        return r;
    }

    @Override
    public TGasStopBill reject(Long id, String approver, String comment) {
        TGasStopBill r = this.gasStopBillMapper.selectById(id);
        if (r == null) {
            return null;
        }
        this.gasStopBillMapper.updateById(r);
        return r;
    }

    @Override
    public TGasStopBill rollback(Long id, String comment) {
        TGasStopBill r = this.gasStopBillMapper.selectById(id);
        if (r == null) {
            return null;
        }
        int node = r.getNodeNo() == null ? 0 : r.getNodeNo();
        r.setNodeNo(Integer.valueOf(Math.max(0, node - 1)));
        this.gasStopBillMapper.updateById(r);
        return r;
    }
}
