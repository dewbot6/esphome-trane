// Headless: extract the CANopen machinery so the panel can be emulated.
// Finds and dumps: (a) functions using CANopen COB-ID base constants
// (0x700 heartbeat, 0x580/0x600 SDO, 0x180/0x200/0x280/0x300/0x380/0x400/0x480/0x500 PDO),
// (b) functions that build a TX standard ID via <<21 (lsl #21) — COB-ID construction,
// (c) functions writing bxCAN filter registers (CAN_base + >=0x200) — the accepted-ID setup.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.address.Address;
import java.io.*;
import java.util.*;

public class DumpCanOpen extends GhidraScript {
    public void run() throws Exception {
        String outPath = System.getenv("CANOPEN_OUT");
        if (outPath == null) outPath = "/tmp/canopen.txt";
        PrintWriter out = new PrintWriter(new FileWriter(outPath));
        long CAN_BASE = 0x40006400L;

        // distinctive CANopen bases; 0x700/0x580/0x600 are strong signals
        int[] strong = {0x700, 0x580, 0x600};
        int[] pdo = {0x180,0x200,0x280,0x300,0x380,0x400,0x480,0x500,0x80};

        Listing listing = currentProgram.getListing();
        DecompInterface dec = new DecompInterface();
        dec.openProgram(currentProgram);

        class Tag { Function f; Set<String> why = new LinkedHashSet<>(); String c; }
        LinkedHashMap<Function,Tag> tagged = new LinkedHashMap<>();
        Tag t;

        for (Function f : listing.getFunctions(true)) {
            if (f.isThunk() || f.isExternal()) continue;
            boolean lsl21=false, filterWrite=false, canbase=false;
            InstructionIterator ii = listing.getInstructions(f.getBody(), true);
            while (ii.hasNext()) {
                Instruction ins = ii.next();
                String m = ins.getMnemonicString().toLowerCase();
                if (m.startsWith("lsl")) {
                    for (int op=0; op<ins.getNumOperands(); op++)
                        for (Object o : ins.getOpObjects(op))
                            if (o instanceof Scalar && ((Scalar)o).getUnsignedValue()==21) lsl21=true;
                }
                for (int op=0; op<ins.getNumOperands(); op++)
                    for (Object o : ins.getOpObjects(op)) {
                        long v = (o instanceof Scalar)?((Scalar)o).getUnsignedValue()
                               :(o instanceof Address)?((Address)o).getOffset():-1;
                        if (v==CAN_BASE) canbase=true;
                        if (v>=CAN_BASE+0x200 && v<=CAN_BASE+0x320) filterWrite=true;
                    }
            }
            if (!(lsl21||filterWrite||canbase)) continue;
            DecompileResults r = dec.decompileFunction(f, 45, monitor);
            if (r==null || !r.decompileCompleted()) continue;
            String c = r.getDecompiledFunction().getC();
            t = new Tag(); t.f=f; t.c=c;
            if (lsl21) t.why.add("TX-id<<21");
            if (filterWrite) t.why.add("filter-reg-write");
            if (canbase) t.why.add("CAN-base");
            // COB-ID base constants in the C text
            List<String> bases = new ArrayList<>();
            for (int b: strong) if (c.contains(String.format("0x%x",b))) bases.add(String.format("0x%x",b));
            for (int b: pdo)    if (c.contains(String.format("0x%x",b))) bases.add(String.format("0x%x",b));
            if (!bases.isEmpty()) t.why.add("COB-bases:"+String.join(",",bases));
            tagged.put(f,t);
        }

        // Also: any function whose C references the strong CANopen bases even w/o CAN regs
        for (Function f : listing.getFunctions(true)) {
            if (f.isThunk()||f.isExternal()||tagged.containsKey(f)) continue;
            DecompileResults r = dec.decompileFunction(f, 30, monitor);
            if (r==null||!r.decompileCompleted()) continue;
            String c = r.getDecompiledFunction().getC();
            List<String> bases = new ArrayList<>();
            for (int b: strong) if (c.contains(String.format("0x%x",b))) bases.add(String.format("0x%x",b));
            if (bases.size()>=1 && (c.contains("0x700")|| bases.size()>=2)) {
                t=new Tag(); t.f=f; t.c=c; t.why.add("COB-bases:"+String.join(",",bases));
                tagged.put(f,t);
            }
        }

        out.println("=== tagged functions ("+tagged.size()+") ===");
        for (Tag x : tagged.values())
            out.println("  "+x.f.getEntryPoint()+"  "+x.f.getName()+"  "+x.why);
        out.println();
        for (Tag x : tagged.values()) {
            out.println("//====================================================");
            out.println("// "+x.f.getName()+" @ "+x.f.getEntryPoint()+"  "+x.why);
            out.println("//====================================================");
            out.println(x.c);
            out.println();
        }
        dec.dispose(); out.close();
        println("Wrote "+outPath+" ("+tagged.size()+" functions)");
    }
}
