$files = Get-ChildItem -Path "mass_server/src/main/java/com/xa/mass/mock" -Recurse -Filter "*.java"

foreach ($file in $files)
{
    $content = Get-Content $file.FullName
    $content = $content -replace 'import com.xa.mass.model', 'import com.xa.mass.core.model'
    $content = $content -replace 'import com.xa.mass.server', 'import com.xa.mass.core.server'
    $content = $content -replace 'import com.xa.mass.engine', 'import com.xa.mass.core.engine'
    Set-Content -Path $file.FullName -Value $content
} 