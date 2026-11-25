import { onBeforeUnmount } from "vue";
import { ElMessage } from "element-plus";

export async function copyText(
  text: string,
  successMessage = "已复制",
  errorMessage = "复制失败，请手动复制。"
): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    ElMessage.success(successMessage);
    return true;
  } catch {
    ElMessage.error(errorMessage);
    return false;
  }
}

/** Shared generation/disposal and focus-return helper for route-driven workbench views. */
export function useViewGuard() {
  let generation = 0;
  let disposed = false;
  let focusReturn: HTMLElement | undefined;

  onBeforeUnmount(() => {
    disposed = true;
    generation++;
  });

  return {
    next: () => ++generation,
    current: () => generation,
    isDisposed: () => disposed,
    isCurrent: (ticket: number) => !disposed && ticket === generation,
    rememberFocus() {
      focusReturn =
        document.activeElement instanceof HTMLElement
          ? document.activeElement
          : undefined;
    },
    restoreFocus() {
      if (focusReturn?.isConnected) focusReturn.focus();
    }
  };
}
