USE `hmdp`;

INSERT INTO `tb_user` (`phone`, `password`, `nick_name`, `icon`)
VALUES (
  'admin',
  'merchantflow-admin-salt@7dd81114a45d0c70184ff01dfb0b57e0',
  'admin',
  '/imgs/icons/default-icon.png'
)
ON DUPLICATE KEY UPDATE
  `password` = VALUES(`password`),
  `nick_name` = VALUES(`nick_name`),
  `icon` = VALUES(`icon`);
