-- 小智医疗问诊智能体 —— 数据库初始化脚本
-- 原仓库只有实体类，没有任何建表 SQL，跑起来必然报 Table 'lizi.appointment' doesn't exist
-- 执行：mysql -uroot -p < create_database.sql

CREATE DATABASE IF NOT EXISTS lizi
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE lizi;

DROP TABLE IF EXISTS appointment;

CREATE TABLE appointment
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，MyBatis-Plus IdType.AUTO 要求自增',
    username    VARCHAR(64)  NOT NULL COMMENT '就诊人姓名',
    id_card     VARCHAR(32)  NOT NULL COMMENT '就诊人身份证号',
    department  VARCHAR(64)  NOT NULL COMMENT '科室名称',
    date        VARCHAR(32)  NOT NULL COMMENT '预约日期（实体类中是 String，不是 DATE）',
    time        VARCHAR(16)  NOT NULL COMMENT '预约时段，取值：上午 / 下午',
    doctor_name VARCHAR(64) DEFAULT NULL COMMENT '医生姓名',
    PRIMARY KEY (id),
    -- AppointmentServiceImpl.getOne 用这 5 个字段 selectOne，
    -- 命中多行会抛 TooManyResultsException，必须靠唯一索引把「同一人同一时段」钉死为一行
    UNIQUE KEY uk_appointment_user_slot (username, id_card, department, date, time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='预约挂号表';
