import type {Component} from 'vue'
import {
    Avatar,
    Compass,
    Connection,
    Cpu,
    DataAnalysis,
    Document,
    FolderOpened,
    List,
    Monitor,
    Notebook,
    Opportunity,
    Setting,
    SetUp,
    Tickets,
    Tools,
    User,
    Warning,
    WarningFilled,
} from '@element-plus/icons-vue'

const iconMap: Record<string, Component> = {
    Avatar,
    Compass,
    Connection,
    Cpu,
    DataAnalysis,
    Document,
    FolderOpened,
    List,
    Monitor,
    Notebook,
    Opportunity,
    Setting,
    SetUp,
    Tickets,
    Tools,
    User,
    Warning,
    WarningFilled,
}

export function resolveMenuIcon(name: string): Component {
    return iconMap[name] ?? Monitor
}
