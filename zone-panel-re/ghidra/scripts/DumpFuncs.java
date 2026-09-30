// Dump specific functions (by address, from env FUNCS="0800d764,08016440,...") with their
// decompilation and their callers. Also, if FIND_CANTX=1, locate functions that write the
// bxCAN TX mailbox identifier register (CAN_base + 0x180 = TI0R) and dump them.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.symbol.*;
import ghidra.program.model.scalar.Scalar;
import java.io.*;
import java.util.*;

public class DumpFuncs extends GhidraScript {
    public void run() throws Exception {
        String outPath = System.getenv("FUNCS_OUT");
        if (outPath == null) outPath = "/tmp/funcs.txt";
        PrintWriter out = new PrintWriter(new FileWriter(outPath));
        Listing listing = currentProgram.getListing();
        FunctionManager fm = currentProgram.getFunctionManager();
        ReferenceManager rm = currentProgram.getReferenceManager();
        DecompInterface dec = new DecompInterface();
        dec.openProgram(currentProgram);

        LinkedHashSet<Function> targets = new LinkedHashSet<>();
        String funcs = System.getenv("FUNCS");
        if (funcs != null) for (String s : funcs.split(",")) {
            if (s.isEmpty()) continue;
            Address a = currentProgram.getAddressFactory().getDefaultAddressSpace()
                        .getAddress(Long.parseLong(s.trim(),16));
            Function f = fm.getFunctionContaining(a);
            if (f != null) targets.add(f);
        }

        long CAN_BASE = 0x40006400L;
        if ("1".equals(System.getenv("FIND_CANTX"))) {
            for (Function f : listing.getFunctions(true)) {
                InstructionIterator ii = listing.getInstructions(f.getBody(), true);
                boolean hit=false;
                while (ii.hasNext()) {
                    Instruction ins = ii.next();
                    for (int op=0; op<ins.getNumOperands(); op++)
                        for (Object o : ins.getOpObjects(op)) {
                            long v=(o instanceof Scalar)?((Scalar)o).getUnsignedValue()
                                  :(o instanceof Address)?((Address)o).getOffset():-1;
                            if (v==CAN_BASE+0x180 || v==CAN_BASE+0x190 || v==CAN_BASE+0x1a0) hit=true;
                        }
                }
                if (hit) targets.add(f);
            }
        }

        for (Function f : targets) {
            out.println("//====================================================");
            out.println("// "+f.getName()+" @ "+f.getEntryPoint());
            // callers
            StringBuilder cs = new StringBuilder();
            for (Reference ref : rm.getReferencesTo(f.getEntryPoint())) {
                Function cf = fm.getFunctionContaining(ref.getFromAddress());
                if (cf!=null) cs.append(cf.getName()+"@"+cf.getEntryPoint()+" ");
            }
            out.println("// callers: "+cs);
            out.println("//====================================================");
            DecompileResults r = dec.decompileFunction(f, 60, monitor);
            if (r!=null && r.decompileCompleted())
                out.println(r.getDecompiledFunction().getC());
            else
                out.println("// <decompile failed>");
            out.println();
        }
        dec.dispose(); out.close();
        println("Wrote "+outPath+" ("+targets.size()+" functions)");
    }
}
