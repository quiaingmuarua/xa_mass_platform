package com.xa.mass.server.task.call;

import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.workerdelivery.json.Jsons;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/** A complete declaration preserves required empty targets during Boot binding. */
@Component
@ConfigurationPropertiesBinding
public final class RefillTargetConfigurationConverter implements Converter<String,RefillTarget> {
    @Override public RefillTarget convert(String source) {
        return RefillTarget.parse(Jsons.parseObject(source));
    }
}
