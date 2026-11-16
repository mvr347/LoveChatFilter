package me.lovelace.lovechatfilter.filters;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.*;
import me.lovelace.lovechatfilter.LoveChatFilter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;

class SafeWordReloadRaceTest {
    ServerMock server; LoveChatFilter plugin;
    @BeforeEach void setup(){server=MockBukkit.mock();plugin=MockBukkit.load(LoveChatFilter.class);}
    @AfterEach void stop(){MockBukkit.unmock();}
    @Test void aMessageDuringRebuildCannotLeaveTheOldBlockCachedForAnHour() throws Exception {
        var player=server.addPlayer("Guest");
        var engine=plugin.getFilterEngine();
        var rebuilding=new CountDownLatch(1);var checked=new CountDownLatch(1);
        var config=new YamlConfiguration(){
            boolean armed;
            @Override public List<String> getStringList(String path){
                if(path.equals("badwords-filter.list")&&armed){
                    armed=false;rebuilding.countDown();
                    try{assertTrue(checked.await(3,TimeUnit.SECONDS));}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                }
                return super.getStringList(path);
            }
        };
        config.loadFromString(plugin.getConfigManager().getConfig().saveToString());
        config.set("badwords-filter.list",List.of("жест", "сука"));
        var field=plugin.getConfigManager().getClass().getDeclaredField("config");field.setAccessible(true);field.set(plugin.getConfigManager(),config);
        engine.rebuildFilters();
        assertNotEquals("жестко",engine.processContent(player,"жестко",FilterEngine.ContentType.CHAT));
        var armed=config.getClass().getDeclaredField("armed");armed.setAccessible(true);armed.setBoolean(config,true);
        var chat=new CompletableFuture<String>();
        Thread.ofVirtual().start(()->{
            try{
                assertTrue(rebuilding.await(3,TimeUnit.SECONDS));
                chat.complete(engine.processContent(player,"жестко",FilterEngine.ContentType.CHAT));
            }catch(Throwable error){chat.completeExceptionally(error);}
            finally{checked.countDown();}
        });
        plugin.addWord("safe","жестко","Admin");
        assertNotEquals("жестко",chat.get(3,TimeUnit.SECONDS),"this message ran against the old scanner");
        assertEquals("жестко",engine.processContent(player,"жестко",FilterEngine.ContentType.CHAT));
        assertFalse(engine.preview("жестко").profane());
        assertFalse(engine.isProfane("ЖЁСТКО!"));
        assertTrue(engine.isProfane("жестко сука"),"a safe word cannot exempt unrelated profanity");
    }
}
