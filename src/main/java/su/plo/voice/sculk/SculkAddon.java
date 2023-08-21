package su.plo.voice.sculk;

import com.google.common.collect.Maps;
import com.google.inject.Inject;
import org.jetbrains.annotations.NotNull;
import su.plo.config.provider.ConfigurationProvider;
import su.plo.config.provider.toml.TomlConfiguration;
import su.plo.voice.api.addon.AddonInitializer;
import su.plo.voice.api.addon.AddonLoaderScope;
import su.plo.voice.api.addon.annotation.Addon;
import su.plo.voice.api.audio.codec.AudioDecoder;
import su.plo.voice.api.audio.codec.CodecException;
import su.plo.voice.api.encryption.EncryptionException;
import su.plo.voice.api.event.EventPriority;
import su.plo.voice.api.event.EventSubscribe;
import su.plo.voice.api.server.PlasmoVoiceServer;
import su.plo.voice.api.server.audio.capture.ServerActivation;
import su.plo.voice.api.server.event.audio.source.PlayerSpeakEndEvent;
import su.plo.voice.api.server.event.audio.source.PlayerSpeakEvent;
import su.plo.voice.api.server.event.config.VoiceServerConfigReloadedEvent;
import su.plo.voice.api.server.event.connection.UdpClientDisconnectedEvent;
import su.plo.voice.api.server.player.VoiceServerPlayer;
import su.plo.voice.api.util.AudioUtil;
import su.plo.voice.proto.data.audio.codec.CodecInfo;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Addon(id = "pv-addon-sculk", scope = AddonLoaderScope.SERVER, version = "1.0.0", authors = {"Apehum"})
public final class SculkAddon implements AddonInitializer {

    private static final ConfigurationProvider toml = ConfigurationProvider.getProvider(TomlConfiguration.class);

    private final Map<String, AudioDecoder> decoders = Maps.newHashMap();
    private Path voiceChatRecordsDirectory;
    private final Map<UUID, Long> lastActivationByPlayerId = Maps.newConcurrentMap();
    private final Map<UUID, ConcurrentLinkedQueue<short[]>> audioBuffers = new ConcurrentHashMap<>();


    @Inject
    private PlasmoVoiceServer voiceServer;
    private SculkConfig config;

    @Override
    public void onAddonInitialize() {
        try {
            voiceChatRecordsDirectory = Paths.get(voiceServer.getConfigsFolder().getPath(), "voice_chat_records");
            if (!Files.exists(voiceChatRecordsDirectory)) {
                Files.createDirectories(voiceChatRecordsDirectory);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create voice_chat_records directory", e);
        }
        loadConfig();
    }

    @EventSubscribe
    public void onConfigLoaded(@NotNull VoiceServerConfigReloadedEvent event) {
        loadConfig();
    }

    @EventSubscribe
    public void onClientDisconnect(@NotNull UdpClientDisconnectedEvent event) {
        lastActivationByPlayerId.remove(event.getConnection()
                .getPlayer()
                .getInstance()
                .getUUID()
        );
    }

    @EventSubscribe(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerSpeak(@NotNull PlayerSpeakEvent event) {
        if (event.getResult() == ServerActivation.Result.IGNORED) return;

        var activation = voiceServer.getActivationManager()
                .getActivationById(event.getPacket().getActivationId());
        if (activation.isEmpty()) return;

        Optional<Boolean> activationEnabled = config.activations().getByActivationName(activation.get().getName());
        if (event.getPacket().getDistance() == 0) {
            if (!activationEnabled.orElse(config.activations().getDefault())) return;
        } else if (activationEnabled.isPresent() && !activationEnabled.get()) {
            return;
        }

        var player = (VoiceServerPlayer) event.getPlayer();
        if (!config.sneakActivation() && player.getInstance().isSneaking()) return;

        var lastActivation = lastActivationByPlayerId.getOrDefault(player.getInstance().getUUID(), 0L);


        var packet = event.getPacket();

        short[] decoded;
        try {
            decoded = decode(activation.get(), packet.getData(), packet.isStereo() && activation.get().isStereoSupported());
        } catch (CodecException | EncryptionException e) {
            e.printStackTrace();
            return;
        }
        audioBuffers.computeIfAbsent(player.getInstance().getUUID(), k -> new ConcurrentLinkedQueue<>()).add(decoded);
        if (System.currentTimeMillis() - lastActivation < 500L) return;
        if (!AudioUtil.containsMinAudioLevel(decoded, config.activationThreshold())) return;

        lastActivationByPlayerId.put(player.getInstance().getUUID(), System.currentTimeMillis());

        player.getInstance().getWorld().sendGameEvent(
                player.getInstance(),
                config.gameEvent()
        );
    }

    @EventSubscribe(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerSpeakEnd(@NotNull PlayerSpeakEndEvent event) {
        var player = event.getPlayer();
        UUID playerId = player.getInstance().getUUID();

        // Get the buffer for this player
        ConcurrentLinkedQueue<short[]> buffer = audioBuffers.get(playerId);
        if (buffer == null) {
            // This should never happen if a PlayerSpeakEndEvent is always preceded by a PlayerSpeakEvent
            System.err.println("No audio data for player " + playerId);
            return;
        }

        List<short[]> allAudioDataList = new ArrayList<>();
        short[] array;
        while ((array = buffer.poll()) != null) {
            allAudioDataList.add(array);
        }

        // Convert the buffer list to an array
        int totalLength = allAudioDataList.stream().mapToInt(arr -> arr.length).sum();
        short[] allAudioData = new short[totalLength];

        int currentIndex = 0;
        for (short[] audioData : allAudioDataList) {
            System.arraycopy(audioData, 0, allAudioData, currentIndex, audioData.length);
            currentIndex += audioData.length;
        }
        // Write the buffer to a file and clear it
        int sampleRate = voiceServer.getConfig().voice().sampleRate();
        Instant timestamp = Instant.ofEpochMilli(System.currentTimeMillis());
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.of("Europe/Moscow"));
        String formattedDate = formatter.format(timestamp);
        writeAudioToWav(allAudioData, player.getInstance().getName() + "_" + formattedDate + ".wav", sampleRate);
        buffer.clear();
    }


    private void writeAudioToWav(short[] audioData, String filename, int sampleRate) {
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, false, false);
        byte[] byteData = new byte[audioData.length * 2];
        ByteBuffer.wrap(byteData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(audioData);
        try {
            AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(byteData), format, audioData.length);
            File outputFile = voiceChatRecordsDirectory.resolve(filename).toFile();
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, outputFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadConfig() {
        try {
            File addonFolder = new File(voiceServer.getConfigsFolder(), "pv-addon-sculk");
            File configFile = new File(addonFolder, "config.toml");

            this.config = toml.load(SculkConfig.class, configFile, false);
            toml.save(SculkConfig.class, config, configFile);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load config", e);
        }
    }

    private short[] decode(@NotNull ServerActivation activation, byte[] data, boolean isStereo)
            throws CodecException, EncryptionException {
        var serverConfig = voiceServer.getConfig();

        var encryption = voiceServer.getDefaultEncryption();
        data = encryption.decrypt(data);

        var encoderInfo = activation.getEncoderInfo()
                .orElseGet(() -> new CodecInfo("opus", Maps.newHashMap()));

        var codecName = encoderInfo.getName() + (isStereo ? "_stereo" : "_mono");
        var decoder = decoders.computeIfAbsent(
                codecName,
                (codec) -> {
                    if (encoderInfo.getName().equals("opus")) {
                        return voiceServer.createOpusDecoder(isStereo);
                    }

                    int sampleRate = serverConfig.voice().sampleRate();
                    return voiceServer.getCodecManager().createDecoder(
                            encoderInfo,
                            sampleRate,
                            isStereo,
                            (sampleRate / 1_000) * 20,
                            serverConfig.voice().mtuSize()
                    );
                }
        );

        decoder.reset();
        return decoder.decode(data);
    }
}