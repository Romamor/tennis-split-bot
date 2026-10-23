import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import ru.movereon.tennis.application.*;
import ru.movereon.tennis.storage.Database;

/** Differential replay: run with old and new application JARs, then compare each snapshot and outcome. */
class CheckRefactoring {
    static SettlementService service;
    static Path path;
    static AtomicInteger queries=new AtomicInteger();
    static Access author=new Access(-1,1,false),admin=new Access(-1,3,true),player=new Access(-1,2,false);
    static int request=0;
    static String quote(String value) { return "\""+value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")+"\""; }
    static String snapshot() throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+path)) {
            for(String table:List.of("users","groups","group_users","group_admins","trainings","training_players","transfers","actions","balance_entries")) {
                List<String> rows=new ArrayList<>();
                try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT * FROM "+table)) {
                    while(r.next()) {
                        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                        try(DataOutputStream out=new DataOutputStream(bytes)) {
                            for(int column=1;column<=r.getMetaData().getColumnCount();column++) {
                                String value=r.getString(column);
                                out.writeBoolean(value!=null);
                                if(value!=null) { byte[] encoded=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);out.writeInt(encoded.length);out.write(encoded); }
                            }
                        }
                        rows.add(Base64.getEncoder().encodeToString(bytes.toByteArray()));
                    }
                }
                Collections.sort(rows);
                hash.update((table+"\n"+String.join("\n",rows)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    static void run(String name,Access access,SettlementCommand command) throws Exception {
        queries.set(0);String outcome;
        try { ActionReceipt result=service.execute(access,"replay-"+(request++),command,null);outcome=result.getId()+":"+result.getVersion(); }
        catch(IllegalArgumentException e) { outcome=e.getClass().getSimpleName()+":"+e.getMessage(); }
        System.out.println("{\"step\":"+quote(name)+",\"outcome\":"+quote(outcome)+",\"sql\":"+queries.get()+",\"snapshot\":"+quote(snapshot())+"}");
    }
    public static void main(String[] args) throws Exception {
        path=Path.of(args[0]);if(Files.exists(path)) throw new IllegalArgumentException("Use a new synthetic database path");
        Database db=new Database(path,sql->{queries.incrementAndGet();return kotlin.Unit.INSTANCE;},false);
        service=new SettlementService(db,Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"),ZoneOffset.UTC));
        for(long group:List.of(-1L,-2L)) {
            service.register(new SettlementGroup(group,"Synthetic group "+(-group),"Europe/Moscow"));
            for(int user=1;user<=3;user++) { service.remember(new Account(user,"Player "+user,"",null,false));service.rememberMembership(group,user,true); }
        }
        run("create",author,new SettlementCommand.CreateTraining("t","Tennis","2026-10-23","18:30"));
        run("join",author,new SettlementCommand.ChangeAttendance("t",1,AttendanceChange.JOIN,0));
        run("time",author,new SettlementCommand.ChangeAttendance("t",1,AttendanceChange.SET_MINUTES,90));
        run("paid",author,new SettlementCommand.ChangeAttendance("t",1,AttendanceChange.SET_PAID,300));
        run("add",author,new SettlementCommand.AddPlayers("t",service.training(author,"t").getVersion(),List.of(2L)));
        TrainingRecord t=service.training(author,"t");Attendance old=t.getPlayers().stream().filter(p->p.getUserId()==2).findFirst().orElseThrow();
        run("save draft",player,new SettlementCommand.SaveAttendance("t",2,old,new Attendance(2,true,60,0,0,old.getOrdinal(),false,0)));
        run("finish",author,new SettlementCommand.FinishTraining("t",service.training(author,"t").getVersion()));
        run("deny closed change",player,new SettlementCommand.ChangeAttendance("t",2,AttendanceChange.SET_MINUTES,120));
        run("deny reopen",player,new SettlementCommand.ReopenTraining("t",service.training(author,"t").getVersion()));
        run("reopen",author,new SettlementCommand.ReopenTraining("t",service.training(author,"t").getVersion()));
        run("leave",player,new SettlementCommand.ChangeAttendance("t",2,AttendanceChange.LEAVE,0));
        run("join again",player,new SettlementCommand.ChangeAttendance("t",2,AttendanceChange.JOIN,0));
        old=service.training(author,"t").getPlayers().stream().filter(p->p.getUserId()==2).findFirst().orElseThrow();
        run("save draft removal",player,new SettlementCommand.SaveAttendance("t",2,old,new Attendance(2,false,0,0,0,old.getOrdinal(),false,0)));
        run("add again",author,new SettlementCommand.AddPlayers("t",service.training(author,"t").getVersion(),List.of(2L)));
        run("finish again",author,new SettlementCommand.FinishTraining("t",service.training(author,"t").getVersion()));
        run("pending",player,new SettlementCommand.SendOtherPayment("p",1,50,false));
        run("edit pending",player,new SettlementCommand.EditPaymentAmount("p",1,75));
        run("deny other edit",author,new SettlementCommand.EditPaymentAmount("p",2,100));
        run("confirm",author,new SettlementCommand.ReceivePayment("p",2L));
        run("admin edit",admin,new SettlementCommand.EditPaymentAmount("p",3,90));
        run("admin cancel",admin,new SettlementCommand.CancelPayment("p",4));
        run("admin record",admin,new SettlementCommand.RecordAdminPayment("a",2,1,40));
        run("legacy record",player,new SettlementCommand.RecordTransfer("l",2,1,20,"2026-10-23","Synthetic",null,false));
        run("legacy edit",player,new SettlementCommand.EditTransferAmount("l",1,25,false));
        long amount=service.paymentSuggestions(author,false,0).getItems().getFirst().getAmount();
        run("calculated send",author,new SettlementCommand.SendPayment("c",2,amount));
        run("cancel pending",author,new SettlementCommand.CancelPayment("c",1));
        run("isolate group",new Access(-2,3,true),new SettlementCommand.EditPaymentAmount("p",5,10));
        System.err.println(db.verify());
    }
}
