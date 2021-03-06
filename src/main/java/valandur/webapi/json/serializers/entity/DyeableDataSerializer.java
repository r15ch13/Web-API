package valandur.webapi.json.serializers.entity;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.spongepowered.api.data.manipulator.mutable.DyeableData;
import valandur.webapi.json.serializers.WebAPISerializer;

import java.io.IOException;

public class DyeableDataSerializer extends WebAPISerializer<DyeableData> {
    @Override
    public void serialize(DyeableData value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeString(value.type().get().getId());
    }
}
