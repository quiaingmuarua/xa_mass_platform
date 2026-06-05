import {spawn} from 'node:child_process'

process.env.VITE_USE_MOCK_API ??= 'true'
process.env.VITE_USE_MOCK_AUTH ??= 'true'

const command = process.platform === 'win32' ? 'cmd.exe' : 'corepack'
const args =
  process.platform === 'win32'
    ? ['/d', '/s', '/c', 'corepack pnpm build']
    : ['pnpm', 'build']

const child = spawn(command, args, {
  stdio: 'inherit',
  env: process.env,
})

child.on('exit', (code) => {
  process.exit(code ?? 1)
})
