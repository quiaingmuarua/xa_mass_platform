> Historical experiment directory, not mainline code.
>
> `v2/` contains a functional-refactor experiment that was not merged into the current runtime path.
> - Tests under `engine/src/test/java/.../v2/**` are excluded from active surefire execution
> - Documents in this directory describe experimental design, not current verified behavior
> - For the current engine path, start with:
>   - `xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java`
>   - `xa-mass-engine/README.md`
>   - `doc/engine/TASK_EXECUTION_FLOW.md`
