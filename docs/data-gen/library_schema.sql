-- 图书借阅库（新域验证：完全通用性，非电商）——分析 agent 自动产出生成计划 → 配置驱动生成
CREATE TABLE member (
  id BIGINT NOT NULL AUTO_INCREMENT,
  member_no VARCHAR(32) NOT NULL COMMENT '会员编号（唯一）',
  name VARCHAR(50) NOT NULL COMMENT '姓名',
  phone VARCHAR(20) COMMENT '电话',
  email VARCHAR(100) COMMENT '邮箱',
  level TINYINT NOT NULL DEFAULT 1 COMMENT '等级 1普通 2银卡 3金卡',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '状态 1正常 2冻结',
  created_at DATETIME NOT NULL COMMENT '注册时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_member_no (member_no)
);

CREATE TABLE book (
  id BIGINT NOT NULL AUTO_INCREMENT,
  title VARCHAR(100) NOT NULL COMMENT '书名',
  author VARCHAR(50) COMMENT '作者',
  price DECIMAL(8,2) NOT NULL COMMENT '定价',
  category VARCHAR(20) COMMENT '分类',
  published DATE COMMENT '出版日期',
  PRIMARY KEY (id)
);

CREATE TABLE borrow (
  id BIGINT NOT NULL AUTO_INCREMENT,
  borrow_no VARCHAR(32) NOT NULL COMMENT '借阅编号（唯一）',
  member_id BIGINT NOT NULL COMMENT '借阅人（引用 member.id）',
  book_id BIGINT NOT NULL COMMENT '图书（引用 book.id）',
  borrow_date DATETIME NOT NULL COMMENT '借出时间',
  return_date DATETIME COMMENT '归还时间',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '状态 1借出 2已还 3逾期',
  PRIMARY KEY (id),
  UNIQUE KEY uk_borrow_no (borrow_no)
);
