package valandur.webapi.json.serializers.entity;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.spongepowered.api.entity.Entity;
import valandur.webapi.cache.DataCache;
import valandur.webapi.json.JsonConverter;
import valandur.webapi.json.serializers.WebAPISerializer;

import java.io.IOException;

public class EntitySerializer extends WebAPISerializer<Entity> {
    @Override
    public void serialize(Entity value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        boolean details = provider.isEnabled(MapperFeature.AUTO_DETECT_CREATORS);
        gen.writeRawValue(JsonConverter.toString(DataCache.getEntity(value), details));
    }
}
