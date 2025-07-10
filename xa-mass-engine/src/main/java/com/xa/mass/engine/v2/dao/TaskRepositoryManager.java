package com.xa.mass.engine.v2.dao;

import com.xa.mass.base.channel.queue.InMemoryMessageMap;
import com.xa.mass.base.channel.queue.MessageMap;
import com.xa.mass.engine.v2.entity.DeviceEntity;
import com.xa.mass.engine.v2.entity.TaskEntity;

public class TaskRepositoryManager {

    //deviceId device map
    private MessageMap<String, DeviceEntity> deviceEntityMessageMap=new InMemoryMessageMap<>();

    //taskId task map
    private MessageMap<String , TaskEntity> taskEntityMessageMap=new InMemoryMessageMap<>();







}
