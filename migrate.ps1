# 创建目标目录
$targetBase = "mass_core/src/main/java/com/xa/mass/core"

# 迁移 mass_server
Copy-Item -Path "mass_server/src/main/java/com/xa/mass/server/*" -Destination "$targetBase/server" -Recurse -Force

# 迁移 mass_engine
Copy-Item -Path "mass_engine/src/main/java/com/xa/mass/engine/*" -Destination "$targetBase/engine" -Recurse -Force

# 迁移 mass_model
Copy-Item -Path "mass_model/src/main/java/com/xa/mass/model/*" -Destination "$targetBase/model" -Recurse -Force

# 复制资源文件
Copy-Item -Path "mass_server/src/main/resources/*" -Destination "mass_core/src/main/resources" -Recurse -Force
Copy-Item -Path "mass_engine/src/main/resources/*" -Destination "mass_core/src/main/resources" -Recurse -Force
Copy-Item -Path "mass_model/src/main/resources/*" -Destination "mass_core/src/main/resources" -Recurse -Force 