// Find the damper-drive logic: functions that write STM32F303 GPIO output registers
// (GPIO ports at 0x48000000..0x48001800, BSRR @ +0x18 / ODR @ +0x14) and look for
// timed driving. Dump their decompilation + callers to see where the drive duration
// comes from (a local constant, an SDO/OD variable, or an SC360-sent command).
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.address.Address;
import ghidra.program.model.symbol.*;
import java.io.*;
import java.util.*;

public class FindDamper extends GhidraScript {
    public void run() throws Exception {
        String out = System.getenv("DAMPER_OUT");
        if (out == null) out = "/tmp/damper.txt";
        PrintWriter w = new PrintWriter(new FileWriter(out));
        Listing listing = currentProgram.getListing();
        FunctionManager fm = currentProgram.getFunctionManager();
        ReferenceManager rm = currentProgram.getReferenceManager();
        DecompInterface dec = new DecompInterface();
        dec.openProgram(currentProgram);

        long[] gpio = {0x48000000L,0x48000400L,0x48000800L,0x48000C00L,0x48001000L,0x48001400L,0x48001800L};
        Set<Long> gpioSet = new HashSet<>();
        for (long g : gpio) gpioSet.add(g);

        LinkedHashSet<Function> hits = new LinkedHashSet<>();
        for (Function f : listing.getFunctions(true)) {
            if (f.isThunk()||f.isExternal()) continue;
            boolean g=false;
            InstructionIterator ii = listing.getInstructions(f.getBody(), true);
            while (ii.hasNext()) {
                Instruction ins = ii.next();
                for (int op=0; op<ins.getNumOperands(); op++)
                    for (Object o : ins.getOpObjects(op)) {
                        long v=(o instanceof Scalar)?((Scalar)o).getUnsignedValue()
                              :(o instanceof Address)?((Address)o).getOffset():-1;
                        if (gpioSet.contains(v & 0xFFFFFC00L)) g=true;
                    }
            }
            if (g) hits.add(f);
        }
        w.println("=== functions referencing GPIO ports ("+hits.size()+") ===");
        for (Function f : hits) {
            StringBuilder cs=new StringBuilder();
            for (Reference r : rm.getReferencesTo(f.getEntryPoint())) {
                Function cf=fm.getFunctionContaining(r.getFromAddress());
                if (cf!=null) cs.append(cf.getName()).append(" ");
            }
            w.println("  "+f.getEntryPoint()+"  "+f.getName()+"   <- "+cs);
        }
        w.println();
        for (Function f : hits) {
            DecompileResults r = dec.decompileFunction(f, 50, monitor);
            if (r==null||!r.decompileCompleted()) continue;
            w.println("//==== "+f.getName()+" @ "+f.getEntryPoint()+" ====");
            w.println(r.getDecompiledFunction().getC());
            w.println();
        }
        dec.dispose(); w.close();
        println("Wrote "+out+" ("+hits.size()+" funcs)");
    }
}
