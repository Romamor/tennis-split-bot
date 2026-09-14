import java.nio.file.Path;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import ru.movereon.tennis.storage.Database;
import ru.movereon.tennis.application.*;
import ru.movereon.tennis.selfservice.*;
import java.util.*;
class MeasurePollList {
 public static void main(String[] args) {
  if(java.nio.file.Files.exists(Path.of(args[0])))throw new IllegalArgumentException("Use a new database path for the synthetic fixture");
  AtomicInteger queries=new AtomicInteger();
  Database db=new Database(Path.of(args[0]),sql->{queries.incrementAndGet();return kotlin.Unit.INSTANCE;},false);
  Clock clock=Clock.fixed(Instant.parse("2026-09-14T10:00:00Z"),ZoneOffset.UTC);
  SettlementService s=new SettlementService(db,clock);
  s.register(new SettlementGroup(-1,"Тестовая группа","Europe/Moscow"));
  for(int i=1;i<=40;i++){s.remember(new Account(i,"Игрок "+i,"",null,false));s.rememberMembership(-1,i,true);}
  s.execute(new Access(-1,1,true),"admin",new SettlementCommand.SetAdministrator(1,true),null);
  TrainingPolls polls=new TrainingPolls(s,clock);polls.setEnabled(new Access(-1,1,true),true);
  for(int i=0;i<100;i++)polls.create(new Access(-1,2,false),"poll-"+i,"Теннис "+i,"2026-09-14","18:30","Не приду");
  InteractionStore state=new InteractionStore(db,clock);Screens screens=new Screens(s,state,"demo_tennis_bot");
  ScreenAction action=kotlinx.serialization.json.Json.Default.decodeFromString(ScreenAction.Companion.serializer(),"{\"kind\":\"poll_list\",\"group\":-1}");
  queries.set(0);db.getReadTransactions().set(0);db.getWriteTransactions().set(0);
  var out=screens.render(action,new Access(-1,1,false),"measure",1L,null,null,false,Set.of(),List.of());
  System.out.println("{\"sql\":"+queries.get()+",\"read_transactions\":"+db.getReadTransactions().get()+",\"write_transactions\":"+db.getWriteTransactions().get()+",\"rows\":"+out.getKeyboard().getRows().size()+"}");
 }
}
