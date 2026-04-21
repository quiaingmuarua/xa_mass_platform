import 'vue-router'
import type { AppRouteMeta } from '@/types/routes'

declare module 'vue-router' {
    interface RouteMeta extends AppRouteMeta {}
}

export {}
