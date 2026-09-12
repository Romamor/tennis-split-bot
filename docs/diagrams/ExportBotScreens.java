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
 static InputForm form(String kind,String id,String more){return Json.Default.decodeFromString(InputForm.Companion.serializer(),"{\"kind\":"+q(kind)+",\"group\":-1,\"training\":"+q(id)+",\"title\":\"Теннис\",\"date\":\"2026-09-12\",\"time\":\"18:30\""+(more.isEmpty()?"":","+more)+"}");}
 static void sql(String query,Object...args)throws Exception{try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+file);PreparedStatement p=c.prepareStatement(query)){for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);p.executeUpdate();}}
 static void run(Access a,SettlementCommand c){svc.execute(a,"fixture"+(serial++),c,null);}
 static void capture(String key,String kind,String id,String more,InputForm f,boolean group,String note){
  ScreenAction a=action(kind,id,more);Screens.Output result=screens.render(a,auth,"capture:"+user+":"+key,user,f,note,group,Set.of(3L));
  if(out.length()>1)out.append(',');out.append("{\"role\":").append(user).append(",\"key\":").append(q(key)).append(",\"group\":").append(group).append(",\"text\":").append(q(result.getText())).append(",\"html\":").append(q(result.getRichHtml())).append(",\"rows\":[");
  boolean firstRow=true;for(var row:result.getKeyboard().getRows()){if(!firstRow)out.append(',');firstRow=false;out.append('[');boolean first=true;for(var b:row){if(!first)out.append(',');first=false;ScreenAction target=null;String token=b.getCallbackData()!=null?b.getCallbackData().substring(2):b.getUrl()!=null&&b.getUrl().contains("n_")?b.getUrl().split("n_",2)[1]:null;if(token!=null&&store.button(token)!=null)target=store.button(token).getAction();out.append("{\"text\":").append(q(b.getText())).append(",\"target\":").append(target==null?"null":store.getJson().encodeToString(ScreenAction.Companion.serializer(),target)).append('}');}out.append(']');}out.append("]}");
 }
 static void cap(String key,String kind,String id)throws Exception{capture(key,kind,id,"",null,false,null);}
 static void phase(String id,String phase)throws Exception{sql("UPDATE trainings SET status=? WHERE id=?",phase,id);}
 static void attendance(String id,boolean playing,int mins,int paid,int guests)throws Exception{sql("INSERT INTO training_players(group_id,training_id,user_id,playing,minutes,guest_minutes,guest_count,paid,ordinal) VALUES(-1,?,?,?,?,?,?,?,99) ON CONFLICT(group_id,training_id,user_id) DO UPDATE SET playing=excluded.playing,minutes=excluded.minutes,guest_minutes=excluded.guest_minutes,guest_count=excluded.guest_count,paid=excluded.paid",id,user,playing?1:0,mins,playing&&guests>0?mins:0,playing?guests:0,paid);}
 static void transferState(String id,String status)throws Exception{sql("UPDATE transfers SET status=?,reviewer=?,review_party=? WHERE id=?",status,status.equals("REVIEW")?user:null,status.equals("REVIEW")?user:null,id);}
 public static void main(String[]args)throws Exception{
 for(user=1;user<=3;user++){
  file=args[0]+"-"+user+".sqlite";if(Files.exists(Path.of(file)))throw new IllegalArgumentException("Use a fresh temporary prefix");db=new Database(Path.of(file),null,false);Clock clock=Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"),ZoneOffset.UTC);svc=new SettlementService(db,clock);store=new InteractionStore(db,clock);screens=new Screens(svc,store,"demo_tennis_bot");auth=new Access(-1,user,user==3);Access superA=new Access(-1,3,true);
  String[] names={"Алексей","Борис","Вера","Глеб","Денис","Елена","Ирина","Кирилл","Лена","Максим"};for(int i=1;i<=10;i++)svc.remember(new Account(i,names[i-1],"","demo_player_"+i,false));for(long g:List.of(-1L,-2L)){svc.register(new SettlementGroup(g,g==-1?"Теннис по субботам":"Вечерний теннис","Europe/Moscow","18:30"));for(long i=1;i<=10;i++)svc.rememberMembership(g,i,true);}run(superA,new SettlementCommand.SetAdministrator(2,true));
  for(String id:List.of("own","other")){run(new Access(-1,id.equals("own")?user:3,true),new SettlementCommand.CreateTraining(id,"Теннис","2026-09-12","18:30"));run(superA,new SettlementCommand.AddPlayers(id,1,List.of(4L,5L,6L,7L)));for(long p=4;p<=7;p++)run(superA,new SettlementCommand.ChangeAttendance(id,p,AttendanceChange.SET_MINUTES,60));run(superA,new SettlementCommand.ChangeAttendance(id,4,AttendanceChange.SET_PAID,350));run(superA,new SettlementCommand.ChangeAttendance(id,5,AttendanceChange.SET_PAID,400));}
  String pay="pay"+user;run(auth,new SettlementCommand.RecordTransfer(pay,user,4,100,"2026-09-12","За прошлый раз",null,false));
  cap("groups","groups","");cap("menu","menu","");capture("mine","trainings","","\"option\":\"mine\"",null,false,null);
  cap("training_own","training","own");cap("training_other","training","other");cap("preview","preview_finish","own");cap("history","history","own");
  phase("own","CLOSED");cap("training_closed","training","own");capture("mine_closed","trainings","","\"option\":\"mine\"",null,false,null);phase("own","OPEN");
  capture("public","public","other","",null,true,null);capture("personal_before","participation","other","",null,true,null);
  attendance("other",true,0,0,0);capture("personal_joined","participation","other","",null,true,null);capture("personal_time","participation_time","other","",null,true,null);capture("personal_payment","participation_payment","other","",null,true,null);
  attendance("other",true,60,350,1);capture("personal_guest","participation","other","",null,true,null);
  attendance("other",false,0,350,0);capture("personal_left","participation","other","",null,true,null);capture("personal_owner","participation","own","",null,true,null);
  sql("DELETE FROM training_players WHERE training_id='other' AND user_id=?",user);
  for(String kind:List.of("title","date","time","ready"))capture("new_"+kind,"form","","",form(kind,"","\"origin\":{\"kind\":\"menu\",\"group\":-1}"),false,null);
  cap("debts","debts","");cap("balances","balances","");cap("settled","settled","");cap("transfers","transfers","");cap("transfer","transfer",pay);cap("transfer_history","transfer_history",pay);cap("people","transfer_people","");capture("direction","transfer_direction","","\"user\":4",null,false,null);
  for(String kind:List.of("transfer_amount","transfer_ready","transfer_duplicate","transfer_date","transfer_note","edit_transfer_amount","edit_transfer_ready","edit_transfer_duplicate"))capture(kind,"form","","",form(kind,"","\"user\":4,\"amount\":100,\"transfer\":"+q(pay)+",\"similar\":["+q(pay)+"],\"origin\":{\"kind\":\"transfer\",\"group\":-1,\"id\":"+q(pay)+"}"),false,null);
  transferState(pay,"REVIEW");cap("transfer_review","transfer",pay);transferState(pay,"CANCELLED");cap("transfer_cancelled","transfer",pay);transferState(pay,"ACTIVE");
  capture("profile","profile_preview","","\"user\":4,\"back\":{\"kind\":\"transfer_people\",\"group\":-1},\"resume\":{\"kind\":\"transfer_direction\",\"group\":-1,\"user\":4}",null,false,null);capture("exit","exit_confirm","own","\"back\":{\"kind\":\"menu\",\"group\":-1},\"resume\":{\"kind\":\"form\",\"group\":-1}",null,false,null);
  if(user>=2){
   cap("settings","settings","");capture("default_time","form","","",form("default_time","","\"originalTime\":\"18:30\",\"origin\":{\"kind\":\"settings\",\"group\":-1}"),false,null);
   capture("all","trainings","","\"option\":\"all\"",null,false,null);cap("roster","roster","own");
   var p=svc.training(auth,"own").getPlayers().get(0);var d=new AttendanceDraft("own",4,p,p,0,action("roster","own",""));store.attendanceDraft(user,-1,d);capture("player","player","own","\"user\":4",null,false,null);
   capture("paid","form","own","",form("paid","own","\"user\":4,\"origin\":{\"kind\":\"player\",\"group\":-1,\"id\":\"own\",\"user\":4}"),false,null);
   sql("UPDATE training_players SET paid=400 WHERE training_id='own' AND user_id=4");capture("player_conflict","player","own","\"user\":4",null,false,null);store.attendanceDraft(user,-1,null);sql("UPDATE training_players SET paid=350 WHERE training_id='own' AND user_id=4");
   InputForm add=form("add_players","own","\"selectedUsers\":[8,9],\"origin\":{\"kind\":\"roster\",\"group\":-1,\"id\":\"own\"}");capture("add_players","add_players","own","",add,false,null);capture("pick_players","form","own","",form("pick_players","own",""),false,null);
   for(String kind:List.of("title","date","time","ready"))capture("edit_"+kind,"form","own","",form(kind,"own","\"origin\":{\"kind\":\"training\",\"group\":-1,\"id\":\"own\"}"),false,null);
   phase("own","REVIEW");cap("training_review","training","own");cap("preview_review","preview_finish","own");cap("cancel","cancel_confirm","own");phase("own","CANCELLED");cap("training_cancelled","training","own");phase("own","OPEN");
   store.sending("training:-1:own",-1,-1,null);store.deliveryResult("training:-1:own","FAILED",null,null);store.pinStatus("training:-1:own","FAILED");cap("training_failure","training","own");cap("recover","recover_confirm","own");phase("own","CLOSED");store.pinStatus("training:-1:own","UNPIN_FAILED");cap("training_unpin_failure","training","own");phase("own","OPEN");
  }
  if(user==3){cap("administrators","administrators","");cap("candidates","admin_candidates","");capture("role_member","admin_person","","\"user\":8",null,false,null);capture("role_admin","admin_person","","\"user\":2",null,false,null);capture("role_super","admin_person","","\"user\":3",null,false,null);run(auth,new SettlementCommand.SetAdministrator(3,true));capture("role_super_extra","admin_person","","\"user\":3",null,false,null);}
 }
 Files.writeString(Path.of(args[0]+".json"),out.append(']').toString());System.out.println("Screens exported: "+args[0]+".json");
 }
}
