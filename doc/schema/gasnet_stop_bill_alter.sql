-- 燃气管段停用四级审批流程 —— 存量库一次性迁移脚本
-- 全新部署只需执行 gasnet.sql + gasnet_seed.sql，无需执行本文件。
-- 库：hai_006

-- 1) 停用签核单补关联管段/申请理由/终审时间，流程由 0..2 三道扩为 0..4 四级
ALTER TABLE t_gas_stop_bill
    ADD COLUMN pipe_id bigint DEFAULT NULL COMMENT '申请停用管段 t_gas_pipe.id' AFTER bill_no,
    ADD COLUMN pipe_no varchar(64) DEFAULT NULL COMMENT '管段档案代号(申请时快照)' AFTER pipe_id,
    ADD COLUMN pipe_name varchar(128) DEFAULT NULL COMMENT '管段档案名称(申请时快照)' AFTER pipe_no,
    ADD COLUMN apply_reason varchar(1000) DEFAULT NULL COMMENT '停用申请理由(重大隐患/到达设计寿命等)' AFTER pipe_name,
    MODIFY COLUMN node_no int DEFAULT NULL COMMENT '当前所在道 0运维申请(退回补充) 1安全审核 2工程评估 3管理批准 4已终审',
    MODIFY COLUMN status int DEFAULT NULL COMMENT '报批情形 0在核 1已核讫(已停用) 2已否决',
    ADD COLUMN finish_time datetime DEFAULT NULL COMMENT '终审批准并执行停用时间' AFTER status,
    ADD UNIQUE KEY uk_gas_stop_bill_no (bill_no),
    ADD KEY idx_gas_stop_pipe (pipe_id),
    ADD KEY idx_gas_stop_status (status, node_no);

-- 2) 签批履历表（新表，gasnet.sql 中为 CREATE TABLE IF NOT EXISTS）
CREATE TABLE IF NOT EXISTS t_gas_stop_approve (
  id bigint NOT NULL COMMENT '主键',
  bill_id bigint NOT NULL COMMENT '所属停用单 t_gas_stop_bill.id',
  bill_no varchar(64) DEFAULT NULL COMMENT '停用申请签核单号(冗余)',
  node_no int DEFAULT NULL COMMENT '审批环节 0运维申请 1安全审核 2工程评估 3管理批准',
  node_name varchar(32) DEFAULT NULL COMMENT '审批环节名称(快照)',
  action int DEFAULT NULL COMMENT '动作 0提交申请 1同意 2退回补充 3否决 4补充后重新提交',
  approve_by varchar(64) DEFAULT NULL COMMENT '操作/审批人',
  approve_comment varchar(1000) DEFAULT NULL COMMENT '申请理由或审批意见',
  approve_time datetime DEFAULT NULL COMMENT '操作/审批时间',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id),
  KEY idx_gas_stop_approve_bill (bill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='燃气管段停用签批履历';

-- 3) 巡检任务补关联管段（停用管段的任务自动扫描与手动派单一律拦截）
ALTER TABLE t_gas_plan_task
    ADD COLUMN pipe_id bigint DEFAULT NULL COMMENT '关联管段 t_gas_pipe.id(管段停用后不再派单)' AFTER content;
