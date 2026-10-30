$ErrorActionPreference = 'Stop'
# Only create explicitly named test schemas and a test vhost; never reset the demo database.
docker.exe exec relayops-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -e "CREATE DATABASE IF NOT EXISTS relayops_test CHARACTER SET utf8mb4; CREATE DATABASE IF NOT EXISTS relayops_messaging_test CHARACTER SET utf8mb4; CREATE DATABASE IF NOT EXISTS relayops_upgrade_test CHARACTER SET utf8mb4; GRANT ALL PRIVILEGES ON relayops_test.* TO ''relayops''@''%''; GRANT ALL PRIVILEGES ON relayops_messaging_test.* TO ''relayops''@''%''; GRANT ALL PRIVILEGES ON relayops_upgrade_test.* TO ''relayops''@''%'';"'
if ($LASTEXITCODE -ne 0) { throw 'Could not prepare isolated MySQL test schemas.' }
docker.exe exec relayops-rabbitmq-1 rabbitmqctl add_vhost relayops-test
if ($LASTEXITCODE -ne 0) { throw 'Could not prepare RabbitMQ test vhost.' }
docker.exe exec relayops-rabbitmq-1 sh -c 'rabbitmqctl set_permissions -p relayops-test "$RABBITMQ_DEFAULT_USER" ".*" ".*" ".*"'
if ($LASTEXITCODE -ne 0) { throw 'Could not grant the local test user permissions.' }
Write-Output 'Isolated schemas and vhost ready.'
