package com.xa.mass.engine.v2.schedule;

import com.xa.mass.base.enums.Project;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.engine.v2.dao.DeviceRepositoryManager;
import com.xa.mass.engine.v2.dao.TaskRepositoryManager;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Objects;

@Slf4j
public class AutoRecoveryService extends AbstractDaemonService{
    TaskRepositoryManager taskRepositoryManager;
    DeviceRepositoryManager deviceRepositoryManager;
    Project project;

    public AutoRecoveryService(TaskRepositoryManager taskRepositoryManager, DeviceRepositoryManager deviceRepositoryManager, Project project){
        this.taskRepositoryManager=taskRepositoryManager;
        this.deviceRepositoryManager=deviceRepositoryManager;
        this.project=project;


    }

    @Override
    public void run() {
            //定时1分钟调度一次
            while(true){
                System.out.println("AutoRecoveryService");
                checkAllTask();
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        

    }


    public void checkAllTask(){
        System.out.println("checkAllTask");
        this.taskRepositoryManager.getProjectTasks(this.project).forEach(taskEntity->{
            long timestampInSeconds = Instant.now().getEpochSecond()*1000;
            if(Objects.equals(taskEntity.getTaskStatus(), "BLOCKED")||taskEntity.getTaskStatus().equals("READY")||taskEntity.getTaskStatus().equals("RUNNING")) {
                if ((taskEntity.getLockExpireTime() < taskEntity.getUpdateTime()) &&(timestampInSeconds-taskEntity.getUpdateTime()>10)){
                    System.out.println("add task to available queue"+taskEntity);
                        taskRepositoryManager.addTaskToAvailableQueue(taskEntity);
                        taskEntity.setLockExpireTime(timestampInSeconds);
                }
            }

        });

    }


    public void checkAllDevice(){
        System.out.println("checkAllDevice");
        this.deviceRepositoryManager.getAllDevices().forEach(deviceEntity->{    
            if(deviceEntity.getLockExpireTime()<deviceEntity.getUpdateTime() && (Instant.now().getEpochSecond()*1000-deviceEntity.getUpdateTime()>10) && deviceEntity.getDeviceStatus().equals("ONLINE")){
                System.out.println("add device to available queue"+deviceEntity);
//                deviceRepositoryManager.addDeviceToAvailableQueue(deviceEntity);
//                deviceEntity.setLockExpireTime(Instant.now().getEpochSecond()*1000);
            }
        });
    }




}
