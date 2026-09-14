import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import ru.movereon.tennis.application.*;
import ru.movereon.tennis.selfservice.*;
import ru.movereon.tennis.storage.*;
import ru.movereon.tennis.telegram.*;
import kotlinx.serialization.json.Json;

class ExportBotScreens {
 static Database db;static SettlementService svc;static InteractionStore store;static Screens screens;static Access auth;static long user;static String file;static StringBuilder out=new StringBuilder("[");static int serial=0;
 static String q(String s){if(s==null)return "null";return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\"";}
 static ScreenAction action(String kind,String id,String more){return Json.Default.decodeFromString(ScreenAction.Companion.serializer(),"{\"kind\":"+q(kind)+",\"group\":-1,\"id\":"+q(id)+(more.isEmpty()?"":","+more)+"}");}
 static InputForm form(String kind,String id,String more){boolean global=kind.startsWith("default_")||id.isEmpty()&&Set.of("title","date","time","poll_decline","group","ready").contains(kind);return Json.Default.decodeFromString(InputForm.Companion.serializer(),"{\"kind\":"+q(kind)+",\"group\":"+(global?0:-1)+",\"training\":"+q(id)+",\"title\":\"Теннис\",\"date\":\"2026-09-12\",\"time\":\"18:30\""+(more.isEmpty()?"":","+more)+"}");}
 static void sql(String query,Object...args)throws Exception{try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+file);PreparedStatement p=c.prepareStatement(query)){for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);p.executeUpdate();}}
 static void run(Access a,SettlementCommand c){svc.execute(a,"fixture"+(serial++),c,null);}
 static void capture(String key,String kind,String id,String more,InputForm f,boolean group,String note){
  ScreenAction a=action(kind,id,more);if(f!=null && f.getGroup()==0 || Set.of("menu","groups","my_trainings","settings","training_settings").contains(kind))a=Json.Default.decodeFromString(ScreenAction.Companion.serializer(),store.getJson().encodeToString(ScreenAction.Companion.serializer(),a).replaceFirst("\"group\":-1","\"group\":0"));Screens.Output result=screens.render(a,auth,"capture:"+user+":"+key,user,f,note,group,Set.of(3L),svc.knownGroups().stream().map(g->new GroupOption(g,svc.isAdmin(new Access(g.getId(),user,user==3)),user==3,true)).toList());
  if(out.length()>1)out.append(',');out.append("{\"role\":").append(user).append(",\"key\":").append(q(key)).append(",\"group\":").append(group).append(",\"text\":").append(q(result.getText())).append(",\"html\":").append(q(result.getRichHtml())).append(",\"rows\":[");
  boolean firstRow=true;for(var row:result.getKeyboard().getRows()){if(!firstRow)out.append(',');firstRow=false;out.append('[');boolean first=true;for(var b:row){if(!first)out.append(',');first=false;ScreenAction target=null;String token=b.getCallbackData()!=null?b.getCallbackData().substring(2):b.getUrl()!=null&&b.getUrl().contains("n_")?b.getUrl().split("n_",2)[1]:null;if(token!=null&&store.button(token)!=null)target=store.button(token).getAction();out.append("{\"text\":").append(q(b.getText())).append(",\"style\":").append(q(b.getStyle())).append(",\"target\":").append(target==null?"null":store.getJson().encodeToString(ScreenAction.Companion.serializer(),target)).append('}');}out.append(']');}out.append("]}");
 }
 static void alert(String key,String id,String back){
  String text;try{svc.requireOpen(svc.training(auth,id));throw new IllegalStateException("Expected closed training");}
  catch(ru.movereon.tennis.core.AccountingException failure){text=failure.getMessage();}
  popup(key,text,back);
 }
 static void popup(String key,String text,String back){
  if(out.length()>1)out.append(',');out.append("{\"role\":").append(user).append(",\"key\":").append(q(key)).append(",\"group\":true,\"text\":").append(q(text)).append(",\"html\":null,\"rows\":[[{\"text\":\"ОК\",\"target\":{\"kind\":\"alert_back\",\"option\":").append(q(back)).append("}}]]}");
 }
 static void cap(String key,String kind,String id)throws Exception{capture(key,kind,id,"",null,false,null);}
 static void phase(String id,String phase)throws Exception{sql("UPDATE trainings SET status=? WHERE id=?",phase,id);}
 static void attendance(String id,boolean playing,int mins,int paid,int guests)throws Exception{sql("INSERT INTO training_players(group_id,training_id,user_id,playing,minutes,guest_minutes,guest_count,paid,ordinal) VALUES(-1,?,?,?,?,?,?,?,99) ON CONFLICT(group_id,training_id,user_id) DO UPDATE SET playing=excluded.playing,minutes=excluded.minutes,guest_minutes=excluded.guest_minutes,guest_count=excluded.guest_count,paid=excluded.paid",id,user,playing?1:0,mins,playing&&guests>0?mins:0,playing?guests:0,paid);}
 static void transferState(String id,String status)throws Exception{sql("UPDATE transfers SET status=?,reviewer=?,review_party=? WHERE id=?",status,status.equals("REVIEW")?user:null,status.equals("REVIEW")?user:null,id);}
 public static void main(String[]args)throws Exception{
 for(user=1;user<=3;user++){
  file=args[0]+"-"+user+".sqlite";if(Files.exists(Path.of(file)))throw new IllegalArgumentException("Use a fresh temporary prefix");db=new Database(Path.of(file),null,false);Clock clock=Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"),ZoneOffset.UTC);svc=new SettlementService(db,clock);store=new InteractionStore(db,clock);screens=new Screens(svc,store,"demo_tennis_bot");auth=new Access(-1,user,user==3);Access superA=new Access(-1,3,true);
  String[] names={"Алексей","Борис","Вера","Глеб","Денис","Елена","Ирина","Кирилл","Лена","Максим"};for(int i=1;i<=10;i++)svc.remember(new Account(i,names[i-1],"","demo_player_"+i,false));for(long g:List.of(-1L,-2L)){svc.register(new SettlementGroup(g,g==-1?"Теннис по субботам":"Вечерний теннис","Europe/Moscow"));for(long i=1;i<=10;i++)svc.rememberMembership(g,i,true);}run(superA,new SettlementCommand.SetAdministrator(2,true));
  for(String id:List.of("own","other")){run(new Access(-1,id.equals("own")?user:3,true),new SettlementCommand.CreateTraining(id,"Теннис","2026-09-12","18:30"));run(superA,new SettlementCommand.AddPlayers(id,1,List.of(4L,5L,6L,7L)));for(long p=4;p<=7;p++)run(superA,new SettlementCommand.ChangeAttendance(id,p,AttendanceChange.SET_MINUTES,60));run(superA,new SettlementCommand.ChangeAttendance(id,4,AttendanceChange.SET_PAID,350));run(superA,new SettlementCommand.ChangeAttendance(id,5,AttendanceChange.SET_PAID,400));}
  String pay="pay"+user;run(auth,new SettlementCommand.RecordTransfer(pay,user,4,100,"2026-09-12","За прошлый раз",null,false));
  cap("groups","groups","");cap("menu","menu","");cap("mine","my_trainings","");
  cap("training_own","training","own");cap("my_training","my_training","own");cap("training_other",user==1?"my_training":"training","other");cap("history","history","own");
  phase("own","CLOSED");cap("training_closed","training","own");cap("my_closed","my_training","own");cap("mine_closed","my_trainings","");phase("own","OPEN");
  if(user==1){String denied;try{run(auth,new SettlementCommand.EditTraining("other",svc.training(auth,"other").getVersion(),"Не разрешено","2026-09-12","18:30"));throw new IllegalStateException("Expected denial");}catch(ru.movereon.tennis.core.AccountingException failure){denied=failure.getMessage();}popup("edit_denied",denied,"public");}
  capture("public_own","public","own","",null,true,null);
  for(String phase:List.of("CLOSED","CANCELLED")){phase("own",phase);String suffix=phase.toLowerCase();capture("public_"+suffix,"public","own","",null,true,null);alert("alert_"+suffix,"own","public_"+suffix);}phase("own","OPEN");
  phase("other","CANCELLED");cap("my_cancelled","my_training","other");phase("other","OPEN");capture("public","public","other","",null,true,null);capture("personal_before","participation","other","",null,true,null);
  attendance("other",true,0,0,0);capture("personal_joined","participation","other","",null,true,null);capture("personal_time","participation_time","other","",null,true,null);capture("personal_payment","participation_payment","other","",null,true,null);
  attendance("other",true,60,350,1);capture("personal_guest","participation","other","",null,true,null);
  run(auth,new SettlementCommand.ChangeAttendance("other",user,AttendanceChange.LEAVE,0));capture("personal_left","participation","other","",null,true,null);capture("personal_owner","participation","own","",null,true,null);
  sql("DELETE FROM training_players WHERE training_id='other' AND user_id=?",user);
  for(String kind:List.of("title","date","time","group","ready"))capture("new_"+kind,"form","","",form(kind,"","\"origin\":{\"kind\":\"menu\",\"group\":0}"),false,null);
  cap("finance","finance","");cap("finance_balances","finance_balances","");
  run(new Access(-1,4,true),new SettlementCommand.RecordTransfer("seed-finance",4,user,300,"2026-09-12","",null,false));
  cap("finance_send","finance_send","");
  capture("finance_send_confirm","finance_send_confirm","","\"user\":4,\"value\":200,\"back\":{\"kind\":\"finance_send\",\"group\":-1}",null,false,null);
  run(new Access(-1,4,true),new SettlementCommand.RecordTransfer("pending-in",4,user,150,"2026-09-12","",null,false));
  run(new Access(-1,4,true),new SettlementCommand.ChangeTransfer("pending-in",1,TransferChange.REVIEW,null));
  cap("finance_receive","finance_receive","");cap("finance_history","finance_history","");
  capture("finance_receive_confirm","finance_receive_confirm","pending-in","\"back\":{\"kind\":\"finance_receive\",\"group\":-1}",null,false,null);
  run(auth,new SettlementCommand.SendPayment("pending-out",4,350));cap("finance_send_empty","finance_send","");
  run(auth,new SettlementCommand.ReceivePayment("pending-in"));cap("finance_receive_empty","finance_receive","");
  cap("finance_received","finance_received","");
  for(String kind:List.of("payment_to","payment_amount","payment_ready"))
   capture(kind,"form","","",form(kind,"","\"paymentFrom\":"+user+",\"user\":4,\"amount\":75"),false,null);
  run(auth,new SettlementCommand.SendOtherPayment("other-payment",4,75,false));
  capture("payment_duplicate","form","","",form("payment_duplicate","","\"paymentFrom\":"+user+",\"user\":4,\"amount\":75,\"similar\":[\"other-payment\"]"),false,null);
  cap("finance_payment","finance_payment","other-payment");
  capture("payment_previous","finance_payment","other-payment","\"back\":{\"kind\":\"form\",\"group\":-1}",null,false,null);
  capture("finance_history_detail","finance_payment","other-payment","\"back\":{\"kind\":\"finance_history\",\"group\":-1}",null,false,null);
  if(user>=2) {
   for(String kind:List.of("payment_from","payment_to","payment_amount","payment_ready"))
    capture("admin_"+kind,"form","","",form(kind,"","\"paymentFrom\":4,\"user\":5,\"amount\":100,\"adminPayment\":true"),false,null);
   run(auth,new SettlementCommand.RecordAdminPayment("admin-payment",4,5,100));
   cap("admin_payment_saved","finance_payment","admin-payment");
  }
  capture("profile","profile_preview","","\"user\":4,\"back\":{\"kind\":\"finance_send\",\"group\":-1},\"resume\":{\"kind\":\"finance_send_confirm\",\"group\":-1,\"user\":4,\"value\":200}",null,false,null);
  capture("exit","exit_confirm","own","\"back\":{\"kind\":\"menu\",\"group\":-1},\"resume\":{\"kind\":\"form\",\"group\":-1}",null,false,null);
  cap("settings","settings","");cap("training_settings","training_settings","");
  capture("default_time","form","","",form("default_time","","\"defaultValue\":\"18:30\""),false,null);
  capture("default_title","form","","",form("default_title","","\"defaultValue\":\"Теннис\""),false,null);
  capture("choose_finance","groups","","\"option\":\"finance\"",null,false,null);
  if(user>=2){
   capture("choose_manage","groups","","\"option\":\"manage\"",null,false,null);
   capture("all","trainings","","\"option\":\"all\"",null,false,null);
  }
  {
   cap("add_player_list","add_player_list","own");cap("exclude_player_list","exclude_player_list","own");cap("manage_players","manage_players","own");
   for(String kind:List.of("participation","participation_time","participation_payment"))capture("managed_"+kind,kind,"own","\"user\":4,\"back\":{\"kind\":\"manage_players\",\"group\":-1,\"id\":\"own\"}",null,false,null);
   capture("pick_add_player","form","own","",form("pick_add_player","own","\"origin\":{\"kind\":\"add_player_list\",\"group\":-1,\"id\":\"own\"}"),false,null);
   for(String status:List.of("OPEN","CLOSED","CANCELLED")){phase("own",status);capture("status_"+status.toLowerCase(),"training_status","own","\"back\":{\"kind\":\"training\",\"group\":-1,\"id\":\"own\"}",null,false,null);}phase("own","OPEN");
   for(String kind:List.of("title","date","time","ready"))capture("edit_"+kind,"form","own","",form(kind,"own","\"origin\":{\"kind\":\"training\",\"group\":-1,\"id\":\"own\"}"),false,null);
   phase("own","OPEN");cap("training_review","training","own");phase("own","CANCELLED");cap("training_cancelled","training","own");phase("own","OPEN");
  }
  if(user>=2){
   store.sending("training:-1:own",-1,-1,null);store.deliveryResult("training:-1:own","FAILED",null,null);store.pinStatus("training:-1:own","FAILED");cap("training_failure","training","own");cap("recover","recover_confirm","own");phase("own","CLOSED");store.pinStatus("training:-1:own","UNPIN_FAILED");cap("training_unpin_failure","training","own");phase("own","OPEN");
  }
  TrainingPolls polls=new TrainingPolls(svc,clock);
  polls.setEnabled(superA,true);
  var poll=polls.create(auth,"poll-own","Теннис","2026-09-12","18:30","Не приду");
  polls.attach(poll,"demo-poll-"+user,800L);
  cap("menu_polls","menu","");
  for(String kind:List.of("title","date","time","poll_decline","group","ready"))capture("poll_"+kind,"form","","",form(kind,"","\"pollId\":\"poll-own\",\"publishGroup\":-1,\"origin\":{\"kind\":\"menu\",\"group\":0}"),false,null);
  capture("choose_polls","groups","","\"option\":\"polls\"",null,false,null);
  cap("poll_list","poll_list","");cap("poll_detail","poll_detail","poll-own");
  capture("poll_close_confirm","poll_close_confirm","poll-own","",null,true,null);
  if(user>=2){capture("choose_poll_settings","groups","","\"option\":\"poll_settings\"",null,false,null);cap("poll_settings","poll_settings","");}
  polls.vote(8000L,polls.get(-1,"poll-own"),4,1);
  polls.beginClose(auth,"poll-own");polls.stopped(polls.get(-1,"poll-own"));polls.finish(polls.get(-1,"poll-own"));
  capture("poll_training","public","poll-own","",null,true,null);
  if(user==3){cap("administrators","administrators","");cap("candidates","admin_candidates","");capture("role_member","admin_person","","\"user\":8",null,false,null);capture("role_admin","admin_person","","\"user\":2",null,false,null);capture("role_super","admin_person","","\"user\":3",null,false,null);run(auth,new SettlementCommand.SetAdministrator(3,true));capture("role_super_extra","admin_person","","\"user\":3",null,false,null);}
  svc.setGroupTrainingRule(superA,"time",false,"time-off");
  run(auth,new SettlementCommand.CreateTraining("equal","Теннис","2026-09-12","18:30"));
  run(auth,new SettlementCommand.AddPlayers("equal",1,List.of(user,4L)));
  run(auth,new SettlementCommand.ChangeAttendance("equal",user,AttendanceChange.ADJUST_GUESTS,1));
  run(auth,new SettlementCommand.ChangeAttendance("equal",4L,AttendanceChange.SET_PAID,300));
  capture("equal_public","public","equal","",null,true,null);
  capture("equal_personal","participation","equal","",null,true,null);
  svc.setGroupTrainingRule(superA,"guests",false,"guests-off");
  run(auth,new SettlementCommand.CreateTraining("simple","Теннис","2026-09-12","18:30"));
  run(auth,new SettlementCommand.AddPlayers("simple",1,List.of(user,4L)));
  capture("simple_public","public","simple","",null,true,null);
  capture("simple_personal","participation","simple","",null,true,null);
  if(user>=2)cap("rules_settings","poll_settings","");

 }
 Files.writeString(Path.of(args[0]+".json"),out.append(']').toString());System.out.println("Screens exported: "+args[0]+".json");
 }
}
