-- 图书借阅新域（Validation V2 新域验证 DDL）：非电商，验证校验器通用性
-- 关键场景：
--   * borrow.status + borrow_date/return_date → STATUS_FLOW（状态联动时间线）
--   * borrow.member_id/book_id → FK 关联复用
--   * book.stock → 数值列（可走确定性工具）
CREATE TABLE member (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '会员ID',
  name VARCHAR(50) NOT NULL COMMENT '会员姓名',
  phone VARCHAR(20) COMMENT '手机号',
  status TINYINT DEFAULT 0 COMMENT '0正常 1停用'
) ENGINE=InnoDB COMMENT='会员';

CREATE TABLE book (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '书ID',
  title VARCHAR(100) NOT NULL COMMENT '书名',
  author VARCHAR(50) NOT NULL COMMENT '作者',
  isbn VARCHAR(20) COMMENT 'ISBN',
  stock INT NOT NULL COMMENT '库存',
  publish_date DATE COMMENT '出版日期'
) ENGINE=InnoDB COMMENT='图书';

CREATE TABLE borrow (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '借阅ID',
  borrow_no VARCHAR(20) NOT NULL COMMENT '借阅编号',
  member_id BIGINT NOT NULL COMMENT '会员ID',
  book_id BIGINT NOT NULL COMMENT '书ID',
  borrow_date DATETIME NOT NULL COMMENT '借出日期',
  return_date DATETIME COMMENT '归还日期',
  status TINYINT DEFAULT 0 COMMENT '0借出 1已还',
  UNIQUE KEY uk_borrow_no (borrow_no),
  KEY idx_member (member_id),
  KEY idx_book (book_id),
  CONSTRAINT fk_borrow_member FOREIGN KEY (member_id) REFERENCES member (id),
  CONSTRAINT fk_borrow_book FOREIGN KEY (book_id) REFERENCES book (id)
) ENGINE=InnoDB COMMENT='借阅记录';
