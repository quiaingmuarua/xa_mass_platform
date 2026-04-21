import type { Component } from 'vue'
import type { AppRouteMeta } from '@/types/routes'

export type AppRouteComponent =
    | Component
    | (() => Promise<Component>)
    | (() => Promise<{ default: Component }>)

export interface AppRouteRecordRaw {
    path: string
    name?: string
    component?: AppRouteComponent
    redirect?: string
    meta: AppRouteMeta
    children?: AppRouteRecordRaw[]
}

export interface MenuItem {
    path: string
    title: string
    icon: string
    children: MenuItem[]
}
