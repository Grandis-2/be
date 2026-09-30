-- 자동 생성: bash flyway-project/verify.sh --write-schema
-- 정본: flyway-project/migrations/*.sql (이 파일은 직접 수정하지 않는다.)
-- MySQL 8.4 / shop 전용. external_mock은 Mock 저장소가 관리한다.
CREATE DATABASE IF NOT EXISTS shop DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE shop;

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cart_items` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL,
  `option_id` bigint NOT NULL,
  `quantity` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_cart_customer_option` (`customer_id`,`option_id`),
  KEY `ix_cart_option` (`option_id`),
  CONSTRAINT `fk_cart_customer` FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`),
  CONSTRAINT `fk_cart_option` FOREIGN KEY (`option_id`) REFERENCES `product_options` (`id`),
  CONSTRAINT `ck_cart_quantity` CHECK ((`quantity` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `categories` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `parent_id` bigint DEFAULT NULL,
  `code` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `name` varchar(60) NOT NULL,
  `option_filter_definitions` json DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_category_code` (`code`),
  KEY `ix_category_parent` (`parent_id`),
  CONSTRAINT `fk_category_parent` FOREIGN KEY (`parent_id`) REFERENCES `categories` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `customers` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `kakao_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `display_name` varchar(100) NOT NULL,
  `name` varchar(50) DEFAULT NULL,
  `email` varchar(255) DEFAULT NULL,
  `phone_number` varchar(20) DEFAULT NULL,
  `token_version` int NOT NULL DEFAULT '0',
  `default_ship_to_name` varchar(50) DEFAULT NULL,
  `default_ship_to_phone` varchar(20) DEFAULT NULL,
  `default_ship_to_postal_code` varchar(10) DEFAULT NULL,
  `default_ship_to_line1` varchar(200) DEFAULT NULL,
  `default_ship_to_line2` varchar(200) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_customer_kakao` (`kakao_id`),
  CONSTRAINT `ck_customer_default_address` CHECK ((((`default_ship_to_name` is null) and (`default_ship_to_phone` is null) and (`default_ship_to_postal_code` is null) and (`default_ship_to_line1` is null) and (`default_ship_to_line2` is null)) or ((`default_ship_to_name` is not null) and (`default_ship_to_phone` is not null) and (`default_ship_to_postal_code` is not null) and (`default_ship_to_line1` is not null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `option_inventories` (
  `option_id` bigint NOT NULL,
  `stock_total` int NOT NULL DEFAULT '0',
  `stock_reserved` int NOT NULL DEFAULT '0',
  `stock_sold` int NOT NULL DEFAULT '0',
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`option_id`),
  CONSTRAINT `fk_inventory_option` FOREIGN KEY (`option_id`) REFERENCES `product_options` (`id`),
  CONSTRAINT `ck_inventory_capacity` CHECK (((`stock_reserved` + `stock_sold`) <= `stock_total`)),
  CONSTRAINT `ck_inventory_nonnegative` CHECK (((`stock_total` >= 0) and (`stock_reserved` >= 0) and (`stock_sold` >= 0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `order_events` (
  `order_id` bigint NOT NULL,
  `event_sequence` bigint NOT NULL,
  `from_status` varchar(30) DEFAULT NULL,
  `to_status` varchar(30) NOT NULL,
  `actor` varchar(10) NOT NULL,
  `reason` varchar(500) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`order_id`,`event_sequence`),
  CONSTRAINT `fk_order_event_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`),
  CONSTRAINT `ck_order_event_actor` CHECK ((`actor` in (_utf8mb4'USER',_utf8mb4'ADMIN',_utf8mb4'SYSTEM')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `order_items` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `product_id` bigint NOT NULL,
  `option_id` bigint NOT NULL,
  `quantity` int NOT NULL,
  `unit_price_snapshot` decimal(12,0) NOT NULL,
  `product_title_snapshot` varchar(100) NOT NULL,
  `option_title_snapshot` varchar(120) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_order_item_option` (`order_id`,`option_id`),
  UNIQUE KEY `uq_order_item_product` (`id`,`product_id`),
  KEY `ix_order_item_option_ref` (`product_id`,`option_id`),
  CONSTRAINT `fk_order_item_option_ref` FOREIGN KEY (`product_id`, `option_id`) REFERENCES `product_options` (`product_id`, `id`),
  CONSTRAINT `fk_order_item_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`),
  CONSTRAINT `ck_order_item_price` CHECK ((`unit_price_snapshot` >= 0)),
  CONSTRAINT `ck_order_item_quantity` CHECK ((`quantity` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `orders` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_token` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `customer_id` bigint NOT NULL,
  `source` varchar(10) NOT NULL,
  `preorder_id` bigint DEFAULT NULL,
  `status` varchar(30) NOT NULL,
  `total_amount` decimal(12,0) NOT NULL,
  `payment_due_at` datetime(6) DEFAULT NULL,
  `stock_released_at` datetime(6) DEFAULT NULL,
  `ship_to_name` varchar(50) NOT NULL,
  `ship_to_phone` varchar(20) NOT NULL,
  `ship_to_postal_code` varchar(10) NOT NULL,
  `ship_to_line1` varchar(200) NOT NULL,
  `ship_to_line2` varchar(200) DEFAULT NULL,
  `internal_note` text,
  `event_sequence` bigint NOT NULL DEFAULT '0',
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_order_token` (`order_token`),
  UNIQUE KEY `uq_order_preorder` (`preorder_id`),
  KEY `ix_order_due` (`status`,`payment_due_at`),
  KEY `ix_order_member_created` (`customer_id`,`created_at`),
  KEY `fk_order_preorder` (`preorder_id`,`customer_id`),
  CONSTRAINT `fk_order_customer` FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`),
  CONSTRAINT `fk_order_preorder` FOREIGN KEY (`preorder_id`, `customer_id`) REFERENCES `preorders` (`id`, `customer_id`),
  CONSTRAINT `ck_order_amount` CHECK ((`total_amount` >= 0)),
  CONSTRAINT `ck_order_due` CHECK (((`source` = _utf8mb4'PREORDER') = (`payment_due_at` is null))),
  CONSTRAINT `ck_order_preorder_link` CHECK (((`source` = _utf8mb4'PREORDER') = (`preorder_id` is not null))),
  CONSTRAINT `ck_order_preorder_no_stock` CHECK (((`source` <> _utf8mb4'PREORDER') or (`stock_released_at` is null))),
  CONSTRAINT `ck_order_source` CHECK ((`source` in (_utf8mb4'PREORDER',_utf8mb4'BUY_NOW',_utf8mb4'CART'))),
  CONSTRAINT `ck_order_status` CHECK ((`status` in (_utf8mb4'AWAITING_PAYMENT',_utf8mb4'AUTHORIZING',_utf8mb4'AWAITING_CONFIRMATION',_utf8mb4'PREPARING_ITEMS',_utf8mb4'READY_TO_SHIP',_utf8mb4'SHIPPED',_utf8mb4'DELIVERED',_utf8mb4'CANCELING',_utf8mb4'CANCELED'))),
  CONSTRAINT `ck_order_stock_released_canceled` CHECK (((`stock_released_at` is null) or (`status` = _utf8mb4'CANCELED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `outbox_events` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `aggregate_type` varchar(30) NOT NULL,
  `aggregate_id` bigint NOT NULL,
  `event_type` varchar(50) NOT NULL,
  `payload` json NOT NULL,
  `publish_attempts` int NOT NULL DEFAULT '0',
  `created_at` datetime(6) NOT NULL,
  `published_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_outbox_event_id` (`event_id`),
  KEY `ix_outbox_unpublished` (`published_at`,`id`),
  CONSTRAINT `ck_outbox_attempts` CHECK ((`publish_attempts` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `payment_transactions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `transaction_type` varchar(10) NOT NULL,
  `provider_order_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL,
  `provider_payment_key` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL,
  `amount` decimal(12,0) NOT NULL,
  `idempotency_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `status` varchar(20) NOT NULL,
  `attempt_count` int NOT NULL DEFAULT '0',
  `next_retry_at` datetime(6) DEFAULT NULL,
  `lease_token` varchar(64) DEFAULT NULL,
  `lease_expires_at` datetime(6) DEFAULT NULL,
  `last_error_code` varchar(100) DEFAULT NULL,
  `last_error_message` varchar(500) DEFAULT NULL,
  `requested_at` datetime(6) DEFAULT NULL,
  `finished_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_payment_tx_idempotency` (`idempotency_key`),
  UNIQUE KEY `uq_payment_tx_provider_order` (`provider_order_id`),
  KEY `ix_payment_tx_order` (`order_id`,`created_at`),
  KEY `ix_payment_tx_next_retry` (`status`,`next_retry_at`),
  KEY `ix_payment_tx_lease` (`status`,`lease_expires_at`),
  CONSTRAINT `fk_payment_tx_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`),
  CONSTRAINT `ck_payment_tx_numbers` CHECK (((`amount` >= 0) and (`attempt_count` >= 0))),
  CONSTRAINT `ck_payment_tx_provider_order` CHECK (((`transaction_type` = _utf8mb4'CAPTURE') = (`provider_order_id` is not null))),
  CONSTRAINT `ck_payment_tx_refund_key` CHECK (((`transaction_type` <> _utf8mb4'REFUND') or (`provider_payment_key` is not null))),
  CONSTRAINT `ck_payment_tx_started_key` CHECK (((`status` = _utf8mb4'PENDING') or (`provider_payment_key` is not null))),
  CONSTRAINT `ck_payment_tx_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'PROCESSING',_utf8mb4'RETRY_SCHEDULED',_utf8mb4'SUCCEEDED',_utf8mb4'FAILED'))),
  CONSTRAINT `ck_payment_tx_type` CHECK ((`transaction_type` in (_utf8mb4'CAPTURE',_utf8mb4'REFUND')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `payments` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `provider_payment_key` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `amount` decimal(12,0) NOT NULL,
  `status` varchar(10) NOT NULL,
  `approved_at` datetime(6) NOT NULL,
  `refunded_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_payment_order` (`order_id`),
  UNIQUE KEY `uq_payment_key` (`provider_payment_key`),
  CONSTRAINT `fk_payment_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`),
  CONSTRAINT `ck_payment_amount` CHECK ((`amount` >= 0)),
  CONSTRAINT `ck_payment_refunded_at` CHECK (((`status` = _utf8mb4'REFUNDED') = (`refunded_at` is not null))),
  CONSTRAINT `ck_payment_status` CHECK ((`status` in (_utf8mb4'SUCCEEDED',_utf8mb4'REFUNDED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `preorder_campaigns` (
  `product_id` bigint NOT NULL,
  `opens_at` datetime(6) NOT NULL,
  `closes_at` datetime(6) NOT NULL,
  `next_queue_position` bigint NOT NULL DEFAULT '1',
  `open_notified_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`product_id`),
  CONSTRAINT `fk_campaign_product` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`),
  CONSTRAINT `ck_campaign_next_position` CHECK ((`next_queue_position` > 0)),
  CONSTRAINT `ck_campaign_period` CHECK ((`closes_at` > `opens_at`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `preorder_events` (
  `preorder_id` bigint NOT NULL,
  `event_sequence` bigint NOT NULL,
  `from_status` varchar(20) DEFAULT NULL,
  `to_status` varchar(20) NOT NULL,
  `actor` varchar(10) NOT NULL,
  `reason` varchar(500) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`preorder_id`,`event_sequence`),
  CONSTRAINT `fk_preorder_event_preorder` FOREIGN KEY (`preorder_id`) REFERENCES `preorders` (`id`),
  CONSTRAINT `ck_preorder_event_actor` CHECK ((`actor` in (_utf8mb4'USER',_utf8mb4'ADMIN',_utf8mb4'SYSTEM'))),
  CONSTRAINT `ck_preorder_event_admin_reason` CHECK (((`actor` <> _utf8mb4'ADMIN') or (`reason` is not null)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `preorder_sync_attempts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `sync_job_id` bigint NOT NULL,
  `attempt_number` int NOT NULL,
  `actor` varchar(10) NOT NULL,
  `result` varchar(20) DEFAULT NULL,
  `http_status` smallint DEFAULT NULL,
  `error_code` varchar(100) DEFAULT NULL,
  `error_message` varchar(500) DEFAULT NULL,
  `started_at` datetime(6) NOT NULL,
  `finished_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sync_attempt_number` (`sync_job_id`,`attempt_number`),
  CONSTRAINT `fk_sync_attempt_job` FOREIGN KEY (`sync_job_id`) REFERENCES `preorder_sync_jobs` (`id`),
  CONSTRAINT `ck_sync_attempt_actor` CHECK ((`actor` in (_utf8mb4'SYSTEM',_utf8mb4'ADMIN'))),
  CONSTRAINT `ck_sync_attempt_http_status` CHECK (((`http_status` is null) or (`http_status` between 100 and 599))),
  CONSTRAINT `ck_sync_attempt_result` CHECK (((`result` is null) or (`result` in (_utf8mb4'SUCCESS',_utf8mb4'TRANSIENT_FAILURE',_utf8mb4'REJECTED',_utf8mb4'UNKNOWN'))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `preorder_sync_jobs` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `preorder_id` bigint NOT NULL,
  `job_type` varchar(10) NOT NULL,
  `request_payload` json NOT NULL,
  `status` varchar(20) NOT NULL,
  `lease_token` varchar(64) DEFAULT NULL,
  `lease_expires_at` datetime(6) DEFAULT NULL,
  `dead_lettered_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sync_job_type` (`preorder_id`,`job_type`),
  KEY `ix_sync_job_lease` (`status`,`lease_expires_at`),
  CONSTRAINT `fk_sync_job_preorder` FOREIGN KEY (`preorder_id`) REFERENCES `preorders` (`id`),
  CONSTRAINT `ck_sync_job_canceled_register_only` CHECK (((`job_type` = _utf8mb4'REGISTER') or (`status` <> _utf8mb4'CANCELED'))),
  CONSTRAINT `ck_sync_job_dead_letter` CHECK (((`status` <> _utf8mb4'DEAD_LETTER') or (`dead_lettered_at` is not null))),
  CONSTRAINT `ck_sync_job_dead_letter_register_only` CHECK (((`job_type` = _utf8mb4'REGISTER') or ((`status` <> _utf8mb4'DEAD_LETTER') and (`dead_lettered_at` is null)))),
  CONSTRAINT `ck_sync_job_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'PROCESSING',_utf8mb4'RETRY_SCHEDULED',_utf8mb4'SUCCEEDED',_utf8mb4'DEAD_LETTER',_utf8mb4'CANCELED'))),
  CONSTRAINT `ck_sync_job_type` CHECK ((`job_type` in (_utf8mb4'REGISTER',_utf8mb4'CANCEL')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `preorders` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `preorder_token` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `customer_id` bigint NOT NULL,
  `product_id` bigint NOT NULL,
  `option_id` bigint NOT NULL,
  `shipment_batch_id` bigint NOT NULL,
  `queue_position` bigint NOT NULL,
  `admission_ticket_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL,
  `idempotency_key` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `product_title_snapshot` varchar(100) NOT NULL,
  `option_title_snapshot` varchar(120) NOT NULL,
  `unit_price_snapshot` decimal(12,0) NOT NULL,
  `status` varchar(20) NOT NULL,
  `active_marker` tinyint GENERATED ALWAYS AS ((case when (`status` <> _utf8mb4'CANCELED') then 1 else NULL end)) STORED,
  `payable_from` datetime(6) DEFAULT NULL,
  `external_reference` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL,
  `internal_note` text,
  `event_sequence` bigint NOT NULL DEFAULT '0',
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_preorder_token` (`preorder_token`),
  UNIQUE KEY `uq_preorder_idempotency` (`customer_id`,`idempotency_key`),
  UNIQUE KEY `uq_preorder_position` (`product_id`,`queue_position`),
  UNIQUE KEY `uq_preorder_id_customer` (`id`,`customer_id`),
  UNIQUE KEY `uq_preorder_external` (`external_reference`),
  UNIQUE KEY `uq_preorder_admission` (`admission_ticket_id`),
  UNIQUE KEY `uq_preorder_active` (`customer_id`,`product_id`,`active_marker`),
  KEY `ix_preorder_payable` (`status`,`payable_from`),
  KEY `ix_preorder_customer_created` (`customer_id`,`created_at`),
  KEY `ix_preorder_option` (`product_id`,`option_id`),
  KEY `ix_preorder_batch` (`product_id`,`shipment_batch_id`),
  CONSTRAINT `fk_preorder_batch` FOREIGN KEY (`product_id`, `shipment_batch_id`) REFERENCES `shipment_batches` (`product_id`, `id`),
  CONSTRAINT `fk_preorder_customer` FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`),
  CONSTRAINT `fk_preorder_option` FOREIGN KEY (`product_id`, `option_id`) REFERENCES `product_options` (`product_id`, `id`),
  CONSTRAINT `ck_preorder_payable_from` CHECK (((`status` <> _utf8mb4'PAYABLE') or (`payable_from` is not null))),
  CONSTRAINT `ck_preorder_position_positive` CHECK ((`queue_position` > 0)),
  CONSTRAINT `ck_preorder_price` CHECK ((`unit_price_snapshot` >= 0)),
  CONSTRAINT `ck_preorder_status` CHECK ((`status` in (_utf8mb4'PENDING_SYNC',_utf8mb4'PAYABLE',_utf8mb4'CANCELING',_utf8mb4'CANCELED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_images` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint NOT NULL,
  `kind` varchar(20) NOT NULL,
  `bundle_key` varchar(60) NOT NULL DEFAULT '',
  `position` int NOT NULL,
  `url` varchar(1000) NOT NULL,
  `is_primary` tinyint(1) NOT NULL DEFAULT '0',
  `primary_marker` tinyint GENERATED ALWAYS AS ((case when `is_primary` then 1 else NULL end)) STORED,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_product_image_position` (`product_id`,`kind`,`bundle_key`,`position`),
  UNIQUE KEY `uq_product_image_primary` (`product_id`,`kind`,`bundle_key`,`primary_marker`),
  CONSTRAINT `fk_product_image_product` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`),
  CONSTRAINT `ck_product_image_kind` CHECK ((`kind` in (_utf8mb4'GALLERY',_utf8mb4'DETAIL'))),
  CONSTRAINT `ck_product_image_position` CHECK ((`position` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_option_axes` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint NOT NULL,
  `axis_key` varchar(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `label` varchar(60) NOT NULL,
  `position` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_option_axis_key` (`product_id`,`axis_key`),
  UNIQUE KEY `uq_option_axis_product` (`product_id`,`id`),
  CONSTRAINT `fk_option_axis_product` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`),
  CONSTRAINT `ck_option_axis_position` CHECK ((`position` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_option_selections` (
  `product_id` bigint NOT NULL,
  `option_id` bigint NOT NULL,
  `axis_id` bigint NOT NULL,
  `value_id` bigint NOT NULL,
  PRIMARY KEY (`option_id`,`axis_id`),
  KEY `ix_option_selection_value` (`value_id`,`option_id`),
  KEY `fk_option_selection_option` (`product_id`,`option_id`),
  KEY `fk_option_selection_axis` (`product_id`,`axis_id`),
  KEY `fk_option_selection_value` (`axis_id`,`value_id`),
  CONSTRAINT `fk_option_selection_axis` FOREIGN KEY (`product_id`, `axis_id`) REFERENCES `product_option_axes` (`product_id`, `id`),
  CONSTRAINT `fk_option_selection_option` FOREIGN KEY (`product_id`, `option_id`) REFERENCES `product_options` (`product_id`, `id`),
  CONSTRAINT `fk_option_selection_value` FOREIGN KEY (`axis_id`, `value_id`) REFERENCES `product_option_values` (`axis_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_option_values` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `axis_id` bigint NOT NULL,
  `value` varchar(60) NOT NULL,
  `normalized_value` varchar(60) NOT NULL,
  `surcharge` decimal(12,0) NOT NULL DEFAULT '0',
  `position` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_option_value` (`axis_id`,`normalized_value`),
  UNIQUE KEY `uq_option_value_axis` (`axis_id`,`id`),
  CONSTRAINT `fk_option_value_axis` FOREIGN KEY (`axis_id`) REFERENCES `product_option_axes` (`id`),
  CONSTRAINT `ck_option_value_position` CHECK ((`position` >= 0)),
  CONSTRAINT `ck_option_value_surcharge` CHECK ((`surcharge` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_options` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint NOT NULL,
  `sku` varchar(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `title` varchar(120) NOT NULL,
  `price` decimal(12,0) NOT NULL,
  `price_overridden` tinyint(1) NOT NULL DEFAULT '0',
  `filter_attributes` json DEFAULT NULL,
  `display_attributes` json DEFAULT NULL,
  `combination_key` varchar(200) DEFAULT NULL,
  `status` varchar(20) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_option_sku` (`product_id`,`sku`),
  UNIQUE KEY `uq_option_product` (`product_id`,`id`),
  UNIQUE KEY `uq_option_combination` (`product_id`,`combination_key`),
  CONSTRAINT `fk_option_product` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`),
  CONSTRAINT `ck_option_price` CHECK ((`price` >= 0)),
  CONSTRAINT `ck_option_status` CHECK ((`status` in (_utf8mb4'ACTIVE',_utf8mb4'PAUSED')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_registrations` (
  `product_id` bigint NOT NULL,
  `idempotency_key` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `requested_visible` tinyint(1) NOT NULL,
  `campaign_set_at` datetime(6) DEFAULT NULL,
  `batches_set_at` datetime(6) DEFAULT NULL,
  `stock_set_at` datetime(6) DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `blocked_reason` varchar(100) DEFAULT NULL,
  `last_error` varchar(500) DEFAULT NULL,
  `lease_token` varchar(64) DEFAULT NULL,
  `lease_expires_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`product_id`),
  UNIQUE KEY `uq_registration_key` (`idempotency_key`),
  CONSTRAINT `fk_registration_product` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`),
  CONSTRAINT `ck_registration_lease` CHECK (((`lease_token` is null) = (`lease_expires_at` is null))),
  CONSTRAINT `ck_registration_outcome` CHECK (((`blocked_reason` is null) or (`completed_at` is null)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `product_reviews` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint NOT NULL,
  `customer_id` bigint NOT NULL,
  `order_item_id` bigint NOT NULL,
  `rating` tinyint NOT NULL,
  `body` varchar(2000) NOT NULL,
  `option_title_snapshot` varchar(120) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_review_order_item` (`order_item_id`),
  KEY `ix_review_product` (`product_id`,`created_at`),
  KEY `ix_review_customer` (`customer_id`,`created_at`),
  KEY `fk_review_order_item` (`order_item_id`,`product_id`),
  CONSTRAINT `fk_review_customer` FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`),
  CONSTRAINT `fk_review_order_item` FOREIGN KEY (`order_item_id`, `product_id`) REFERENCES `order_items` (`id`, `product_id`),
  CONSTRAINT `fk_review_product` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`),
  CONSTRAINT `ck_review_body` CHECK ((char_length(`body`) > 0)),
  CONSTRAINT `ck_review_rating` CHECK ((`rating` between 1 and 5))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `products` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `category_id` bigint NOT NULL,
  `sale_mode` varchar(20) NOT NULL,
  `title` varchar(100) NOT NULL,
  `base_price` decimal(12,0) NOT NULL DEFAULT '0',
  `description` text,
  `image_url` varchar(1000) DEFAULT NULL,
  `tags` varchar(500) DEFAULT NULL,
  `status` varchar(20) NOT NULL,
  `visible` tinyint(1) NOT NULL DEFAULT '1',
  `warranty_offered` tinyint(1) NOT NULL DEFAULT '0',
  `warranty_surcharge` decimal(12,0) NOT NULL DEFAULT '0',
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_product_sale_mode` (`sale_mode`,`status`),
  KEY `ix_product_category` (`category_id`,`status`,`sale_mode`),
  CONSTRAINT `fk_product_category` FOREIGN KEY (`category_id`) REFERENCES `categories` (`id`),
  CONSTRAINT `ck_product_base_price` CHECK ((`base_price` >= 0)),
  CONSTRAINT `ck_product_sale_mode` CHECK ((`sale_mode` in (_utf8mb4'PREORDER',_utf8mb4'IN_STOCK'))),
  CONSTRAINT `ck_product_status` CHECK ((`status` in (_utf8mb4'ACTIVE',_utf8mb4'PAUSED'))),
  CONSTRAINT `ck_product_warranty_surcharge` CHECK ((`warranty_surcharge` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `refresh_tokens` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `customer_id` bigint NOT NULL,
  `family_id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `token_hash` binary(32) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `rotated_at` datetime(6) DEFAULT NULL,
  `revoked_at` datetime(6) DEFAULT NULL,
  `client_ip` varchar(45) DEFAULT NULL,
  `user_agent` varchar(255) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_refresh_token_hash` (`token_hash`),
  KEY `ix_refresh_family` (`family_id`),
  KEY `ix_refresh_customer` (`customer_id`,`revoked_at`),
  KEY `ix_refresh_expires` (`expires_at`),
  CONSTRAINT `fk_refresh_customer` FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`),
  CONSTRAINT `ck_refresh_expiry` CHECK ((`expires_at` > `created_at`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `shipment_batches` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `product_id` bigint NOT NULL,
  `batch_number` int NOT NULL,
  `position_from` bigint NOT NULL,
  `position_to` bigint DEFAULT NULL,
  `open_ended_marker` tinyint GENERATED ALWAYS AS ((case when (`position_to` is null) then 1 else NULL end)) STORED,
  `estimated_ship_start` date NOT NULL,
  `estimated_ship_end` date NOT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_batch_number` (`product_id`,`batch_number`),
  UNIQUE KEY `uq_batch_position_from` (`product_id`,`position_from`),
  UNIQUE KEY `uq_batch_product` (`product_id`,`id`),
  UNIQUE KEY `uq_batch_open_ended` (`product_id`,`open_ended_marker`),
  CONSTRAINT `fk_batch_product` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`),
  CONSTRAINT `ck_batch_positive` CHECK (((`batch_number` > 0) and (`position_from` > 0))),
  CONSTRAINT `ck_batch_range` CHECK (((`position_to` is null) or (`position_to` >= `position_from`))),
  CONSTRAINT `ck_batch_ship_window` CHECK ((`estimated_ship_end` >= `estimated_ship_start`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;
