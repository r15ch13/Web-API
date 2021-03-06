package valandur.webapi.json.serializers.player;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.spongepowered.api.data.manipulator.mutable.entity.GameModeData;
import valandur.webapi.json.serializers.WebAPISerializer;

import java.io.IOException;

public class GameModeDataSerializer extends WebAPISerializer<GameModeData> {
    @Override
    public void serialize(GameModeData value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeObject(value.type().get());
    }
}
