// Headless post-script: find CAN-related functions and dump their decompilation.
// Strategy: locate functions that reference the bxCAN peripheral (0x40006400) or
// perform the STDID extract (>>21), plus any function whose decompiled text mentions
// the known CAN IDs, and write their decompiled C to an output file.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.scalar.Scalar;
import java.io.*;
import java.util.*;

public class DumpCanRouter extends GhidraScript {
    public void run() throws Exception {
        String outPath = System.getenv("CAN_OUT");
        if (outPath == null) outPath = "/tmp/can_router.txt";
        PrintWriter out = new PrintWriter(new FileWriter(outPath));

        long CAN_BASE = 0x40006400L;
        Set<Function> targets = new LinkedHashSet<>();
        Listing listing = currentProgram.getListing();
        ReferenceManager rm = currentProgram.getReferenceManager();

        // 1) functions that reference the CAN peripheral base as data/scalar
        FunctionIterator fit = listing.getFunctions(true);
        for (Function f : listing.getFunctions(true)) {
            InstructionIterator ii = listing.getInstructions(f.getBody(), true);
            boolean hit = false;
            while (ii.hasNext()) {
                Instruction ins = ii.next();
                for (int op = 0; op < ins.getNumOperands() && !hit; op++) {
                    for (Object o : ins.getOpObjects(op)) {
                        if (o instanceof Scalar) {
                            long v = ((Scalar) o).getUnsignedValue();
                            if (v == CAN_BASE) hit = true;
                        }
                        if (o instanceof Address && ((Address) o).getOffset() == CAN_BASE) hit = true;
                    }
                }
                // STDID extract: lsr/lsrs by 21
                String m = ins.getMnemonicString().toLowerCase();
                if (m.startsWith("lsr")) {
                    for (int op = 0; op < ins.getNumOperands(); op++)
                        for (Object o : ins.getOpObjects(op))
                            if (o instanceof Scalar && ((Scalar) o).getUnsignedValue() == 21) hit = true;
                }
            }
            if (hit) targets.add(f);
        }

        out.println("=== RX-handler-shell functions (CAN base / >>21): " + targets.size() + " ===");
        for (Function f : targets)
            out.println("  " + f.getEntryPoint() + "  " + f.getName());
        out.println();

        DecompInterface dec = new DecompInterface();
        dec.openProgram(currentProgram);
        int[] ids = {0x283,0x308,0x310,0x320,0x380,0x381,0x382,0x383,0x384,0x385,0x386,
                     0x387,0x388,0x389,0x38a,0x38b,0x38d,0x38e,0x38f,0x410,0x420,0x430,0x450,
                     0x460,0x490,0x491,0x492,0x493,0x494,0x495,0x4b0,0x4b1,0x4b2,0x4b3,0x4b4,
                     0x4c0,0x5c1,0x5c9,0x641,0x649};

        // Scan EVERY function: count distinct known CAN IDs that appear as constants in its C.
        class Hit { Function f; int n; String c; List<Integer> found; }
        List<Hit> hits = new ArrayList<>();
        int scanned = 0;
        for (Function f : currentProgram.getListing().getFunctions(true)) {
            if (f.isThunk() || f.isExternal()) continue;
            scanned++;
            DecompileResults r = dec.decompileFunction(f, 45, monitor);
            if (r == null || !r.decompileCompleted()) continue;
            String c = r.getDecompiledFunction().getC();
            List<Integer> found = new ArrayList<>();
            for (int id : ids) {
                // match as hex literal 0x... to avoid decimal coincidences
                if (c.contains(String.format("0x%x", id)) || c.contains(String.format("0x%X", id)))
                    found.add(id);
            }
            if (found.size() >= 2) {
                Hit h = new Hit(); h.f = f; h.n = found.size(); h.c = c; h.found = found;
                hits.add(h);
            }
        }
        hits.sort((a, b) -> b.n - a.n);

        out.println("=== functions containing >=2 distinct CAN IDs (scanned " + scanned + ") ===");
        for (Hit h : hits) {
            StringBuilder sb = new StringBuilder();
            for (int id : h.found) sb.append(String.format("0x%03x ", id));
            out.println("  " + h.f.getEntryPoint() + "  " + h.f.getName() + "  [" + h.n + "]: " + sb);
        }
        out.println();
        // Dump full C for the top candidates (the dispatch/decoders)
        for (Hit h : hits) {
            out.println("//======================================================");
            out.println("// " + h.f.getName() + " @ " + h.f.getEntryPoint() + "  (" + h.n + " IDs)");
            out.println("//======================================================");
            out.println(h.c);
            out.println();
        }
        dec.dispose();
        out.close();
        println("Wrote " + outPath + " (" + hits.size() + " candidate functions)");
    }
}
