import type {App, Directive} from 'vue'
import {hasAnyPermission, hasPermission} from '@/utils/permissions'

type PermissionBinding = string | string[]

function isVisible(binding: PermissionBinding): boolean {
    if (Array.isArray(binding)) {
        return hasAnyPermission(binding)
    }

    return hasPermission(binding)
}

export const permissionDirective: Directive<HTMLElement, PermissionBinding> = {
    mounted(element, binding) {
        if (!binding.value) {
            return
        }

        if (!isVisible(binding.value)) {
            element.remove()
        }
    },
}

export function registerPermissionDirective(app: App<Element>): void {
    app.directive('permission', permissionDirective)
}
