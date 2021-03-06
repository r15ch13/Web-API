package valandur.webapi.json.serializers.item;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.spongepowered.api.data.manipulator.mutable.item.SpawnableData;
import valandur.webapi.json.serializers.WebAPISerializer;

import java.io.IOException;

public class SpawnableDataSerializer extends WebAPISerializer<SpawnableData> {
    @Override
    public void serialize(SpawnableData value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeString(value.type().get().getId());
    }
}
