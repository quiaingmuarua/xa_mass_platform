$files = Get-ChildItem -Path "mass_core/src/main/java/com/xa/mass/core" -Recurse -Filter "*.java"

foreach ($file in $files) {
    $content = Get-Content $file.FullName
    $content = $content -replace 'package com.xa.mass.server', 'package com.xa.mass.core.server'
    $content = $content -replace 'package com.xa.mass.engine', 'package com.xa.mass.core.engine'
    $content = $content -replace 'package com.xa.mass.model', 'package com.xa.mass.core.model'
    $content = $content -replace 'import com.xa.mass.server', 'import com.xa.mass.core.server'
    $content = $content -replace 'import com.xa.mass.engine', 'import com.xa.mass.core.engine'
    $content = $content -replace 'import com.xa.mass.model', 'import com.xa.mass.core.model'
    Set-Content -Path $file.FullName -Value $content
} 