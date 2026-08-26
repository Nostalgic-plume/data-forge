-- =========================================================
-- shop 业务库 18 表 DDL（测试夹具）
-- 来源：从 shop 项目实体类反推（原 schema_for_tool.sql 已丢失）
-- 用途：DataForge 冒烟/验收测试的自包含表结构（不依赖外部仓库）
-- =========================================================

CREATE TABLE `user` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  `username` VARCHAR(255) DEFAULT NULL COMMENT '用户名',
  `password` VARCHAR(255) DEFAULT NULL COMMENT '密码（BCrypt加密）',
  `phone` VARCHAR(255) DEFAULT NULL COMMENT '手机号',
  `email` VARCHAR(255) DEFAULT NULL COMMENT '邮箱',
  `nickname` VARCHAR(255) DEFAULT NULL COMMENT '昵称',
  `avatar` VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
  `status` INT DEFAULT 0 COMMENT '状态：0正常 1停用 2注销中 3封禁中',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT NULL COMMENT '更新时间',
  `deleted` INT DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户';

CREATE TABLE `admin` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  `username` VARCHAR(255) DEFAULT NULL COMMENT '登录用户名',
  `password` VARCHAR(255) DEFAULT NULL COMMENT '登录密码（BCrypt加密）',
  `nickname` VARCHAR(255) DEFAULT NULL COMMENT '昵称',
  `admin_level` INT DEFAULT 0 COMMENT '级别：1普通 2超级',
  `status` INT DEFAULT 0 COMMENT '状态：0正常 1停用',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT NULL COMMENT '更新时间',
  `deleted` INT DEFAULT 0 COMMENT '逻辑删除：0未删 1已删'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='管理员';

CREATE TABLE `shop` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  `user_id` BIGINT DEFAULT NULL COMMENT '商家用户ID（关联 user.id）',
  `username` VARCHAR(255) DEFAULT NULL COMMENT '商家登录用户名',
  `password` VARCHAR(255) DEFAULT NULL COMMENT '登录密码（BCrypt加密）',
  `shop_name` VARCHAR(255) DEFAULT NULL COMMENT '店铺名称',
  `shop_logo` VARCHAR(255) DEFAULT NULL COMMENT '店铺logo URL',
  `shop_desc` TEXT COMMENT '店铺描述',
  `contact_name` VARCHAR(255) DEFAULT NULL COMMENT '联系人',
  `contact_phone` VARCHAR(255) DEFAULT NULL COMMENT '联系电话',
  `status` INT DEFAULT 0 COMMENT '状态：0正常 1停用',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT NULL COMMENT '更新时间',
  `deleted` INT DEFAULT 0 COMMENT '逻辑删除：0未删 1已删'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='店铺';

CREATE TABLE `category` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  `name` VARCHAR(255) DEFAULT NULL COMMENT '分类名称',
  `parent_id` BIGINT DEFAULT 0 COMMENT '父分类ID（0为顶级）',
  `sort` INT DEFAULT 0 COMMENT '排序',
  `icon` VARCHAR(255) DEFAULT NULL COMMENT '图标URL',
  `status` INT DEFAULT 0 COMMENT '状态：0正常 1隐藏',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT NULL COMMENT '更新时间',
  `deleted` INT DEFAULT 0 COMMENT '逻辑删除：0未删 1已删'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品分类';

CREATE TABLE `product` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  `name` VARCHAR(255) DEFAULT NULL COMMENT '商品名称',
  `description` TEXT COMMENT '商品描述',
  `category_id` BIGINT DEFAULT NULL COMMENT '分类ID（所属二级分类）',
  `first_category_id` BIGINT DEFAULT NULL COMMENT '一级分类ID',
  `brand` VARCHAR(255) DEFAULT NULL COMMENT '品牌',
  `main_image` VARCHAR(255) DEFAULT NULL COMMENT '主图URL',
  `images` TEXT COMMENT '轮播图（JSON数组）',
  `detail` TEXT COMMENT '详情内容（富文本）',
  `shop_id` BIGINT DEFAULT NULL COMMENT '商家ID',
  `status` INT DEFAULT 0 COMMENT '状态：0待审核 1上架 2下架',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT NULL COMMENT '更新时间',
  `deleted` INT DEFAULT 0 COMMENT '逻辑删除：0未删 1已删'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品（SPU）';

CREATE TABLE `sku` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  `product_id` BIGINT DEFAULT NULL COMMENT '商品ID',
  `name` VARCHAR(255) DEFAULT NULL COMMENT 'SKU名称（如：红色-M码）',
  `specs` VARCHAR(255) DEFAULT NULL COMMENT '规格值',
  `price` DECIMAL(10,2) DEFAULT NULL COMMENT '价格',
  `original_price` DECIMAL(10,2) DEFAULT NULL COMMENT '原价',
  `stock` INT DEFAULT 0 COMMENT '库存',
  `image` VARCHAR(255) DEFAULT NULL COMMENT '图片URL（覆盖主图）',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT NULL COMMENT '更新时间',
  `deleted` INT DEFAULT 0 COMMENT '逻辑删除：0未删 1已删'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品SKU';

CREATE TABLE `address` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  `user_id` BIGINT DEFAULT NULL COMMENT '用户ID',
  `receiver_name` VARCHAR(255) DEFAULT NULL COMMENT '收货人',
  `receiver_phone` VARCHAR(255) DEFAULT NULL COMMENT '收货电话',
  `province` VARCHAR(255) DEFAULT NULL COMMENT '省',
  `city` VARCHAR(255) DEFAULT NULL COMMENT '市',
  `district` VARCHAR(255) DEFAULT NULL COMMENT '区',
  `detail` TEXT COMMENT '详细地址',
  `is_default` INT DEFAULT 0 COMMENT '是否默认：0否 1是',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT NULL COMMENT '更新时间',
  `deleted` INT DEFAULT 0 COMMENT '逻辑删除：0未删 1已删'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='收货地址';

CREATE TABLE `cart` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  `user_id` BIGINT DEFAULT NULL COMMENT '用户ID',
  `product_id` BIGINT DEFAULT NULL COMMENT '商品ID',
  `sku_id` BIGINT DEFAULT NULL COMMENT 'SKU ID',
  `quantity` INT DEFAULT 0 COMMENT '数量',
  `checked` INT DEFAULT 0 COMMENT '是否选中：0未选 1选中',
  `is_ordered` INT DEFAULT 0 COMMENT '是否已下单：0未下单 1已下单',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT NULL COMMENT '更新时间',
  `deleted` INT DEFAULT 0 COMMENT '逻辑删除：0未删 1已删'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='购物车';

CREATE TABLE `orders` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  `order_no` VARCHAR(32) DEFAULT NULL COMMENT '订单编号',
  `user_id` BIGINT DEFAULT NULL COMMENT '用户ID',
  `shop_id` BIGINT DEFAULT NULL COMMENT '商家ID（关联商家用户表）',
  `total_amount` DECIMAL(10,2) DEFAULT NULL COMMENT '订单金额',
  `actual_amount` DECIMAL(10,2) DEFAULT NULL COMMENT '实际支付金额',
  `discount_amount` DECIMAL(10,2) DEFAULT NULL COMMENT '优惠金额',
  `freight` DECIMAL(10,2) DEFAULT NULL COMMENT '运费',
  `status` INT DEFAULT 0 COMMENT '状态：0待支付 1已支付 2已发货 3已收货 4已完成 5已取消 6已退款',
  `receiver_name` VARCHAR(255) DEFAULT NULL COMMENT '收货人',
  `receiver_phone` VARCHAR(255) DEFAULT NULL COMMENT '收货电话',
  `receiver_address` VARCHAR(255) DEFAULT NULL COMMENT '收货地址',
  `pay_type` INT DEFAULT NULL COMMENT '支付方式：1微信 2支付宝',
  `pay_time` DATETIME DEFAULT NULL COMMENT '支付时间',
  `deliver_time` DATETIME DEFAULT NULL COMMENT '发货时间',
  `logistics_no` VARCHAR(32) DEFAULT NULL COMMENT '物流单号',
  `receive_time` DATETIME DEFAULT NULL COMMENT '收货时间',
  `remark` TEXT COMMENT '订单备注',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT NULL COMMENT '更新时间',
  `deleted` INT DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
  UNIQUE KEY `uk_order_no` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单';

CREATE TABLE `order_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  `order_id` BIGINT DEFAULT NULL COMMENT '订单ID',
  `order_no` VARCHAR(32) DEFAULT NULL COMMENT '订单编号',
  `product_id` BIGINT DEFAULT NULL COMMENT '商品ID',
  `product_name` VARCHAR(255) DEFAULT NULL COMMENT '商品名称',
  `sku_id` BIGINT DEFAULT NULL COMMENT 'SKU ID',
  `sku_name` VARCHAR(255) DEFAULT NULL COMMENT 'SKU名称',
  `product_main_image` VARCHAR(255) DEFAULT NULL COMMENT '商品主图快照（下单时的主图URL）',
  `price` DECIMAL(10,2) DEFAULT NULL COMMENT '单价',
  `quantity` INT DEFAULT 0 COMMENT '数量',
  `subtotal` DECIMAL(10,2) DEFAULT NULL COMMENT '小计',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `deleted` INT DEFAULT 0 COMMENT '逻辑删除：0未删 1已删'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单商品项';

CREATE TABLE `permission` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  `name` VARCHAR(255) DEFAULT NULL COMMENT '权限名称',
  `code` VARCHAR(32) DEFAULT NULL COMMENT '权限编码（如 product:add）',
  `parent_id` BIGINT DEFAULT 0 COMMENT '父权限ID（0为顶级）',
  `sort` INT DEFAULT 0 COMMENT '排序',
  `status` INT DEFAULT 0 COMMENT '状态：0正常 1禁用',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT NULL COMMENT '更新时间',
  `deleted` INT DEFAULT 0 COMMENT '逻辑删除：0未删 1已删'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='权限';

CREATE TABLE `role` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  `name` VARCHAR(255) DEFAULT NULL COMMENT '角色名称',
  `code` VARCHAR(32) DEFAULT NULL COMMENT '角色编码：admin（管理员）/ merchant（商家）/ user（普通用户）',
  `description` TEXT COMMENT '角色描述',
  `status` INT DEFAULT 0 COMMENT '状态：0正常 1禁用',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT NULL COMMENT '更新时间',
  `deleted` INT DEFAULT 0 COMMENT '逻辑删除：0未删 1已删'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色';

CREATE TABLE `role_permission` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  `role_id` BIGINT DEFAULT NULL COMMENT '角色ID',
  `permission_id` BIGINT DEFAULT NULL COMMENT '权限ID',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色-权限关联';

CREATE TABLE `user_role` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  `user_id` BIGINT DEFAULT NULL COMMENT '用户ID',
  `role_id` BIGINT DEFAULT NULL COMMENT '角色ID',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户-角色关联';

CREATE TABLE `chat_conversation` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  `user_id` BIGINT DEFAULT NULL COMMENT '用户ID',
  `title` VARCHAR(255) DEFAULT NULL COMMENT '会话标题（首句话摘要）',
  `status` INT DEFAULT 0 COMMENT '状态：0=进行中 1=已结束',
  `total_msgs` INT DEFAULT 0 COMMENT '消息总数',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT NULL COMMENT '更新时间',
  `deleted` INT DEFAULT 0 COMMENT '逻辑删除：0=未删 1=已删'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能客服对话会话';

CREATE TABLE `chat_message` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  `conversation_id` BIGINT DEFAULT NULL COMMENT '所属会话ID',
  `role` VARCHAR(255) DEFAULT NULL COMMENT '角色：user / assistant',
  `content` TEXT COMMENT '消息内容',
  `rag_context` TEXT COMMENT 'RAG 检索上下文明细（JSON格式）',
  `token_count` INT DEFAULT 0 COMMENT '消息Token数',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  CONSTRAINT `fk_chat_message_conversation` FOREIGN KEY (`conversation_id`) REFERENCES `chat_conversation`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能客服对话消息';

CREATE TABLE `chat_feedback` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  `message_id` BIGINT DEFAULT NULL COMMENT '对应的 chat_message.id',
  `user_id` BIGINT DEFAULT NULL COMMENT '用户ID',
  `rating` INT DEFAULT 0 COMMENT '评分：1=有用 0=无用 -1=错误',
  `feedback_text` TEXT COMMENT '用户反馈文字',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能客服对话反馈';

CREATE TABLE `user_status_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  `user_id` BIGINT DEFAULT NULL COMMENT '被操作的用户 ID',
  `operator_id` BIGINT DEFAULT NULL COMMENT '操作人 ID',
  `from_status` INT DEFAULT NULL COMMENT '变更前状态：0正常 1停用 2注销中 3封禁中',
  `to_status` INT DEFAULT NULL COMMENT '变更后状态：0正常 1停用 2注销中 3封禁中',
  `reason` VARCHAR(255) DEFAULT NULL COMMENT '变更原因',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='账号状态变更日志';
