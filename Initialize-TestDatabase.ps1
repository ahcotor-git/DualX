[CmdletBinding()]
param([switch]$CheckOnly)

$ErrorActionPreference = 'Stop'
$container = 'scds-local-mysql'
$credentials = Join-Path $PSScriptRoot '.env.test'

function Invoke-AdminSql([string]$sql) {
    # Password and SQL travel via container environment/stdin, never command-line arguments.
    $result = @($sql | docker exec -i $container sh -c 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD mysql -uroot -N -B' 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw 'MySQL operation failed. Output suppressed to avoid exposing credentials; inspect server state before retrying.'
    }
    return $result
}

$health = docker inspect --format '{{.State.Health.Status}}' $container 2>$null
if ($LASTEXITCODE -ne 0 -or $health -ne 'healthy') {
    throw 'The existing scds-local-mysql container must be healthy. This script does not start or recreate containers.'
}
$portsJson = docker inspect --format '{{json .NetworkSettings.Ports}}' $container
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect MySQL port bindings.' }
$bindings = @((ConvertFrom-Json $portsJson).'3306/tcp')
if ($bindings.Count -ne 1 -or $bindings[0].HostIp -ne '127.0.0.1' -or $bindings[0].HostPort -ne '13306') {
    throw 'Expected MySQL bound only to 127.0.0.1:13306.'
}
$existing = @(Invoke-AdminSql @'
SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'scds_next_test';
SELECT COUNT(*) FROM mysql.user WHERE user = 'scds_test';
'@)
if ($existing.Count -ne 2 -or $existing[0] -ne '0' -or $existing[1] -ne '0' -or (Test-Path $credentials)) {
    throw 'Test database, account or .env.test already exists. Refusing to overwrite, reset or delete anything.'
}
if ($CheckOnly) {
    Write-Host 'Preflight passed: loopback-only healthy MySQL; test database/account/credentials are absent.'
    return
}

$bytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
$password = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()

# Create an owner-only file before writing any secret. Keep it on failure for recovery.
$stream = [System.IO.File]::Open($credentials, [System.IO.FileMode]::CreateNew)
$stream.Dispose()
$owner = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
$acl = New-Object System.Security.AccessControl.FileSecurity
$acl.SetOwner($owner)
$acl.SetAccessRuleProtection($true, $false)
$rule = New-Object System.Security.AccessControl.FileSystemAccessRule(
    $owner, [System.Security.AccessControl.FileSystemRights]::FullControl,
    [System.Security.AccessControl.AccessControlType]::Allow)
$acl.AddAccessRule($rule)
Set-Acl -LiteralPath $credentials -AclObject $acl
$content = "SCDS_TEST_JDBC_URL=jdbc:mysql://127.0.0.1:13306/scds_next_test?rewriteBatchedStatements=true`n" +
    "SCDS_TEST_MYSQL_USER=scds_test`nSCDS_TEST_MYSQL_PASSWORD=$password`n"
[System.IO.File]::WriteAllText($credentials, $content, (New-Object System.Text.UTF8Encoding($false)))

# No global privileges, GRANT OPTION, DROP, user changes or access to the development database.
# Escape underscores: database-level GRANT treats them as pattern wildcards.
$grantDatabase = '`scds\_next\_test`'
$sql = @"
CREATE DATABASE scds_next_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'scds_test'@'%' IDENTIFIED BY '$password';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON $grantDatabase.* TO 'scds_test'@'%';
"@
try {
    $null = Invoke-AdminSql $sql
} finally {
    $password = $null
    $content = $null
    $sql = $null
    [Array]::Clear($bytes, 0, $bytes.Length)
}
Write-Host 'Created scds_next_test and its schema-scoped account; saved owner-only .env.test.'
Write-Host 'No migrations executed here. Retain the database; tests clean only their own records.'
