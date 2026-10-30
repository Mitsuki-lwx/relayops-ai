# 本机 Docker 存储位置

2026-10-29 已将 Docker Desktop 的容器、镜像与数据卷存储迁到 F 盘。

- 配置目录：`F:/DockerData`
- 实际数据盘：`F:/DockerData/disk/docker_data.vhdx`
- 当前版本的原生配置项：`CustomWslDistroDir`；通过 Desktop 设置 API 的 `customWslDistroDir` 更新，等价于设置界面的 WSL 磁盘位置。

迁移前后 14 个容器、全部镜像记录、43 个卷完全一致。MySQL 和 RabbitMQ 健康，工作台可登录并读取迁移前的任务及加密结果。原 C 盘的 `Docker/wsl/disk/docker_data.vhdx` 已由原生迁移移走；不要再次复制旧磁盘覆盖当前文件。

Docker 虚拟机内的数据根仍显示 `/var/lib/docker`，其内容由上述 F 盘数据文件承载。项目继续使用 Compose 命名卷，数据随该磁盘保存。

记录在 `artifacts/docker-storage-migration/`：对象清单、设置备份、初次复制校验及最终验证 JSON。本次额外创建的 F 盘复制副本已删除。启动时遇到的旧零字节 socket 目录已重命名保留，未删除任何业务数据。

本机 4.82 不识别 `WslDataFolder`，该试验字段已清理。Windows DiskImage 的宿主挂载标记和文件独占打开结果不单独用作 WSL 客体磁盘使用状态的判断依据。以 Desktop 原生目录配置、原磁盘迁移记录、对象清单与服务数据验证为准。
