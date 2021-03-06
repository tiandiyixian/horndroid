/*
 * MIT License
 *
 * Copyright (c) 2017 TU Wien
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.horndroid.z3;

import com.horndroid.Options;
import com.horndroid.analysis.Analysis;
import com.horndroid.debugging.Debug;
import com.horndroid.debugging.LHInfo;
import com.horndroid.debugging.MethodeInfo;
import com.horndroid.debugging.RegInfo;
import com.horndroid.model.Report;
import com.horndroid.model.ReportEntry;
import com.horndroid.util.CMPair;
import com.microsoft.z3.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

import static com.horndroid.debugging.QUERY_TYPE.GLOBAL;
import static com.horndroid.debugging.QUERY_TYPE.HIGH;
import static com.horndroid.debugging.QUERY_TYPE.LOCAL;


public class FSEngine extends Z3Clauses {

    private static final Logger LOGGER = LogManager.getLogger(com.horndroid.z3.FSEngine.class);
    boolean initialized = false;
    private FSVariable var;
    private FSFunction func;
    private Options options;
    private Integer localHeapSize;

    private Map<Integer, Expr[]> rPredExpr; // helps generating rPred when there are no changes to the values

    private Map<Integer, Integer> allocationPointOffset;

    private Map<Integer, Integer> allocationPointSize;

    public FSEngine(Options options) {
        try {
            this.options = options;
            bvSize = options.bitvectorSize;
            mQueries = new ArrayList<>();
            //mQueriesDebug = new ArrayList<>();

            Global.setParameter("fixedpoint.engine", "pdr");
            Global.setParameter("fixedpoint.print_answer", "true");

            //Global.setParameter("pdr.flexible_trace", "true");
            Global.setParameter("pp.bv-literals", "false");
            //Global.setParameter("fixedpoint.generate_proof_trace", "true");
            Global.setParameter("pp.pretty_proof", "true");
            Global.setParameter("opt.print_model", "true");

            HashMap<String, String> cfg = new HashMap<String, String>();
            mContext = new Context(cfg); // Context ctx = mContext;
            // mFixedPoint = mContext.mkFixedpoint(); //Fixedpoint fp =
            // mFixedPoint;
            mFuncs = new ArrayList<>();
            mRules = new ArrayList<>();

            // add vars
            var = new FSVariable(mContext, bvSize);

            // add func
            func = new FSFunction(mContext, bvSize);
            this.declareRel(func.getH());
            this.declareRel(func.getHi());
            this.declareRel(func.getI());
            this.declareRel(func.getS());

            this.declareRel(func.getTaint());
            this.declareRel(func.getReach());
            // add main
            BoolExpr b1 = hPred(var.getCn(), var.getCn(), mContext.mkBV("parent".hashCode(), bvSize), var.getF(),
                    var.getLf(), var.getBf());
            BoolExpr b2 = hPred(var.getCn(), var.getCn(), mContext.mkBV("result".hashCode(), bvSize), var.getVal(),
                    var.getLval(), var.getBval());
            BoolExpr b3 = hPred(var.getF(), var.getF(), var.getFpp(), var.getVfp(), var.getLfp(), var.getBfp());
            BoolExpr b1b2b3 = mContext.mkAnd(b1, b2, b3);
            BoolExpr b4 = hPred(var.getF(), var.getF(), mContext.mkBV("result".hashCode(), bvSize), var.getVal(),
                    var.getLval(), var.getBval());
            BoolExpr b1b2b3_b4 = mContext.mkImplies(b1b2b3, b4);

            this.addRule(b1b2b3_b4, null);

            // adding rules for the connected component taint
            // base
            BoolExpr hh1 = hPred(var.getCn(), var.getVfp(),
                    var.getF(),
                    var.getVal(), var.getLf(), var.getBf());
            BoolExpr bb1 = taintPred(var.getVfp(), var.getLf());
            BoolExpr hh1_bb1 = mContext.mkImplies(hh1, bb1);
            this.addRule(hh1_bb1, null);
            // step
            BoolExpr hh2 = mContext.mkAnd(
                    hPred(var.getCn(), var.getVfp(),
                            var.getF(),
                            var.getVal(), var.getLf(), var.getBf()),
                    this.eq(var.getBf(), mContext.mkTrue()),
                    taintPred(var.getVal(), var.getLfp())
            );
            BoolExpr bb2 = taintPred(var.getVfp(), var.getLfp());
            BoolExpr hh2_bb2 = mContext.mkImplies(hh2, bb2);
            this.addRule(hh2_bb2, null);


            // adding rules for the connected component reach
            // base
            BoolExpr hh3 = mContext.mkAnd(
                    hPred(var.getCn(), var.getVfp(),
                            var.getF(),
                            var.getVal(), var.getLf(), var.getBf()),
                    this.eq(var.getBf(), mContext.mkTrue())
            );
            BoolExpr bb3 = reachPred(var.getVfp(), var.getVal());
            BoolExpr hh3_bb3 = mContext.mkImplies(hh3, bb3);
            this.addRule(hh3_bb3, null);
            // step
            BoolExpr hh4 = mContext.mkAnd(
                    hPred(var.getCn(), var.getVfp(),
                            var.getF(),
                            var.getVal(), var.getLf(), var.getBf()),
                    this.eq(var.getBf(), mContext.mkTrue()),
                    reachPred(var.getVal(), var.getRez())
            );
            BoolExpr bb4 = reachPred(var.getVfp(), var.getRez());
            BoolExpr hh4_bb4 = mContext.mkImplies(hh4, bb4);
            this.addRule(hh4_bb4, null);


            if (options.pointersMerge) {
                this.declareRel(func.getJoin());
                /*BoolExpr hi = hPred(var.getCn(),
                        var.getVfp(),
                        var.getVal(), var.getFpp(), var.getLf(), var.getBf());
                BoolExpr bi = joinPred(var.getVfp(), var.getLf());
                BoolExpr hibi = mContext.mkImplies(hi, bi);
                this.addRule(hibi, null);*/
            }
        } catch (Z3Exception e) {
            LOGGER.error("FSEngineFailed", e);
            throw new RuntimeException("Z3Engine Failed");
        }
    }

    public Context getContext() {
        return mContext;
    }


    public void addRule(BoolExpr rule, String symbol) {
        try {
            mRules.add(rule);
        } catch (Z3Exception e) {
            LOGGER.error(e.getMessage());
            throw new RuntimeException("Z3Engine Failed: addRule");
        }
    }
    public void initialize( Integer localHeapSize, Map<Integer,Integer> allocationPointOffset, Map<Integer,Integer> allocationPointSize) {
        if (this.initialized){
            throw new RuntimeException("FSEngine Failed: initialized twice");
        }
        this.localHeapSize = localHeapSize;
        this.allocationPointOffset = allocationPointOffset;
        this.allocationPointSize = allocationPointSize;
        this.var.initialize(localHeapSize);
        this.initialized = true;

        func.setReachLH(this.reachLHDef());
        this.declareRel(func.getReachLH());
        func.setCFilter(this.cFilterDef());
        this.declareRel(func.getCFilter());
        //func.setLiftLH((this.liftLHDef()));

        this.rPredExpr = new HashMap<>();
    }
    public void initializeNFS() {
        if (this.initialized){
            throw new RuntimeException("FSEngine Failed: initialized twice");
        }
        this.localHeapSize = (Integer) 0;
        this.allocationPointOffset = new HashMap<Integer,Integer>();
        this.allocationPointSize = new HashMap<Integer,Integer>();
        this.var.initialize(0);
        this.initialized = true;
        this.rPredExpr = new HashMap<>();
    }
    public Boolean isInitialized() {
        return initialized;
    }

    public Integer getOffset(int instanceNumber){
        return allocationPointOffset.get(instanceNumber);
    }
    public Integer getSize(int instanceNumber){
        return allocationPointSize.get(instanceNumber);
    }

    public FSVariable getVars() {
        return var;
    }

    public FSFunction getFunc() {
        return func;
    }

    public void addQuery(Z3Query query) {
        if (options.maxQueries!=0 && mQueries.size() >= options.maxQueries){
            return;
        }
        boolean sameAsCurrentQuery = QUERY_IS_COMPACT && (mCurrentQuery != null)
                && mCurrentQuery.getClassName().equals(query.getClassName())
                && mCurrentQuery.getMethodName().equals(query.getMethodName())
                && mCurrentQuery.getPc().equals(query.getPc())
                && mCurrentQuery.getSinkName().equals(query.getSinkName());

        if (sameAsCurrentQuery) {
            // merge by or-ing queries
            mCurrentQuery.setQuery(this.or(mCurrentQuery.getQuery(), query.getQuery()));
        } else {
            // start new query
            if (mCurrentQuery != null){
                mQueries.add(mCurrentQuery);
            }
            mCurrentQuery = query;
        }
    }

    public void addQueryDebug(Z3Query query) {
        mQueries.add(query);
    }



    public Report executeAllQueries(Analysis analysis, String tag) {
        Report report = new Report();
        if (mCurrentQuery != null) mQueries.add(mCurrentQuery);

        int numberOfQueries = mQueries.size();
        report.setNumberOfQueries(numberOfQueries);

// Used for debugging
        final Debug debug = new Debug(analysis);
        // Counter of the number of queries
        int counter = 0;
        int currentPrint = 0;
        int percentage = 0;

        LOGGER.info("Number of the generated queries: "+ mQueries.size());

        for (Z3Query mQuery : mQueries) {
            final ReportEntry reportEntry = new ReportEntry();
            final Z3Query q = mQuery;
            boolean isVerbose = q.isVerbose();
            reportEntry.setVerbose(isVerbose);
            reportEntry.setDescription(q.getDescription());

            final Fixedpoint temp = mContext.mkFixedpoint();
            for (BoolExpr rule : mRules) {
                temp.addRule(rule, null);
            }
            for (FuncDecl func : mFuncs) {
                temp.registerRelation(func);
                Symbol[] symbols = new Symbol[]{mContext.mkSymbol("interval_relation"),
                        mContext.mkSymbol("bound_relation")};
                temp.setPredicateRepresentation(func, symbols);
            }
            Status result = temp.query(q.getQuery());

            String res_string = result.toString();

            //if (res_string.equals("SATISFIABLE"))
            //    System.out.println(temp.getAnswer());

            if (res_string.equals("SATISFIABLE"))
                reportEntry.setResult("POTENTIAL LEAK");
            if (res_string.equals("UNSATISFIABLE"))
                reportEntry.setResult("NO LEAK");
            if  (!(res_string.equals("SATISFIABLE")) && !res_string.equals("UNSATISFIABLE"))
                reportEntry.setResult("UNKNOWN");

            report.addReportEntry(reportEntry);

            LOGGER.info(Integer.toString(counter + 1) + " " + reportEntry.getDescription()+":"+reportEntry.getResult());


            boolean isSAT = res_string.equals("SATISFIABLE");
            if (!q.debugging && options.tillFirstLeak && isSAT) {
                break;
            }
            /*
			 * Apparently the Z3 wrapper is not handling the memory correctly,
			 * need to GC manually. See:
			 * http://stackoverflow.com/questions/24188626/performance-issues-
			 * about-z3-for-java#comment37349014_24190067
			 */
            if (counter % 50 == 0) {
                System.gc();
            }
            if ((counter + 1 >= currentPrint + (mQueries.size()/ 10)) && (mQueries.size() > 50)) {
                currentPrint = counter + 1;
                percentage += 10;
                LOGGER.info(percentage + "% of queries handled");
            }

            counter++;

            if (q.debugging && q.isReg) {
                final MethodeInfo minfo = debug.get(q.getClassName(), q.getMethodName());
                boolean res = isSAT;
                switch (q.queryType) {
                    case HIGH:
                        minfo.regInfo[q.regNum].highPut(Integer.parseInt(q.getPc()), res);
                        break;
                    case LOCAL:
                        minfo.regInfo[q.regNum].localPut(Integer.parseInt(q.getPc()), res);
                        break;
                    case GLOBAL:
                        minfo.regInfo[q.regNum].globalPut(Integer.parseInt(q.getPc()), res);
                        break;
                    default:
                        throw new RuntimeException(
                                "In flow sensitive mode received a standard query: " + q.queryType.toString());
                }
            }
            if (q.debugging && q.isLocalHeap) {
                final MethodeInfo minfo = debug.get(q.getClassName(), q.getMethodName());
                boolean res = isSAT;
                // LHKey lhkey = new LHKey(q.instanceNum,q.field);
                final LHInfo lhinf = minfo.getLHInfo(q.instanceNum, q.field);
                final RegInfo regInf = lhinf.getRegInfo();
                Integer k = Integer.parseInt(q.getPc());
                switch (q.queryType) {
                    case HIGH:
                        regInf.highPut(k, res);
                        break;
                    case LOCAL:
                        regInf.localPut(k, res);
                        break;
                    case GLOBAL:
                        regInf.globalPut(k, res);
                        break;
                    default:
                        throw new RuntimeException(
                                "In flow sensitive mode received a standard query: " + q.queryType.toString());
                }
            }
        }

        debug.printToLatex();
        report.setTag(tag);
        return report;
    }


    public void declareRel(FuncDecl funcDecl) {
        try {
            mFuncs.add(funcDecl);
        } catch (Z3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Z3Engine Failed: declareRel");
        }
    }

    public void declareRel(String name, Sort[] domain, Sort range) {
        try {
            FuncDecl f = mContext.mkFuncDecl(name, domain, range);
            this.declareRel(f);
        } catch (Z3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Z3Engine Failed: declareRel");
        }
    }

    public void declareVar(Sort type) {
        try {
            Expr var = mContext.mkBound(0, type);
        } catch (Z3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Z3Engine Failed: declareVar");
        }
    }

    private FuncDecl rPredDef(String c, String m, int pc, int size) {
        try {
            // rPredDef
            BitVecSort bv64 = mContext.mkBitVecSort(bvSize);
            BoolSort bool = mContext.mkBoolSort();

            String funcName = "R_" + c + '_' + m + '_' + Integer.toString(pc);
            Sort[] domains = new Sort[4 * size + 5 * localHeapSize];
            // argument + register + result register
            Arrays.fill(domains, 0, size, bv64);
            // high value and local object label and global object label
            Arrays.fill(domains, size, 4 * size, bool);
            // local heap entries
            Arrays.fill(domains, 4 * size, 4 * size + localHeapSize, bv64);
            // high value and local object label and global object label and abstract filter
            Arrays.fill(domains, 4 * size + localHeapSize, 4 * size + 5 * localHeapSize, bool);
            FuncDecl f = mContext.mkFuncDecl(funcName, domains, mContext.mkBoolSort());
            this.declareRel(f);
            return f;
        } catch (Z3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("FSEngine Failed: rPredDef");
        }
    }

    private Expr[] getExpressions(final Map<Integer, BitVecExpr> rUp,
                                  final Map<Integer, BoolExpr> rUpHigh, final Map<Integer, BoolExpr> rUpLocal,
                                  final Map<Integer, BoolExpr> rUpGlobal, final Map<Integer, BitVecExpr> lHValues,
                                  final Map<Integer, BoolExpr> lHHigh, final Map<Integer, BoolExpr> lHLocal,
                                  final Map<Integer, BoolExpr> lHGlobal, final Map<Integer, BoolExpr> lHFilter,
                                  final int numArg,
                                  final int numReg){
        int size = numArg + numReg + 1;
        boolean noChanges = false;
        if (rUp.isEmpty() && rUpHigh.isEmpty() && rUpLocal.isEmpty() &&
        rUpGlobal.isEmpty() && lHValues.isEmpty() &&
        lHHigh.isEmpty() && lHLocal.isEmpty() &&
        lHGlobal.isEmpty() && lHFilter.isEmpty()){
            if (!rPredExpr.isEmpty()){
                Expr[] expr = rPredExpr.get(size);
                if (expr != null){
                    return expr;
                }
            }
            noChanges = true;
        }
        Expr[] e = new Expr[4 * size + 5 * this.localHeapSize];
        for (int i = 0, j = size, k = 2 * size, l = 3 * size; i < size; i++, j++, k++, l++) {
            e[i] = rUp.get(i);
            if (e[i] == null) {
                e[i] = var.getV(i);
            }
            e[j] = rUpHigh.get(i);
            if (e[j] == null) {
                e[j] = var.getH(i);
            }
            e[k] = rUpLocal.get(i);
            if (e[k] == null) {
                e[k] = var.getL(i);
            }
            e[l] = rUpGlobal.get(i);
            if (e[l] == null) {
                e[l] = var.getG(i);
            }
        }
        ;
        for (int loop = 0,  i = 4 * size, j = 4 * size + this.localHeapSize, k = 4 * size
                + 2 * this.localHeapSize, l = 4 * size + 3 * this.localHeapSize, n = 4 * size
                     + 4 * this.localHeapSize; loop < this.localHeapSize; loop++, i++, j++, k++, l++, n++) {
            e[i] = lHValues.get(loop);
            if (e[i] == null) {
                e[i] = var.getLHV(loop);
            }
            e[j] = lHHigh.get(loop);
            if (e[j] == null) {
                e[j] = var.getLHH(loop);
            }
            e[k] = lHLocal.get(loop);
            if (e[k] == null) {
                e[k] = var.getLHL(loop);
            }
            e[l] = lHGlobal.get(loop);
            if (e[l] == null) {
                e[l] = var.getLHG(loop);
            }
            e[n] = lHFilter.get(loop);
            if (e[n] == null) {
                e[n] = var.getLHF(loop);
            }
        }
        if (noChanges){
            rPredExpr.put(size, e);
        }
        return e;
    }

    public BoolExpr rPred(final String c, final String m, final int pc, final Map<Integer, BitVecExpr> rUp,
                          final Map<Integer, BoolExpr> rUpHigh, final Map<Integer, BoolExpr> rUpLocal,
                          final Map<Integer, BoolExpr> rUpGlobal, final Map<Integer, BitVecExpr> lHValues,
                          final Map<Integer, BoolExpr> lHHigh, final Map<Integer, BoolExpr> lHLocal,
                          final Map<Integer, BoolExpr> lHGlobal, final Map<Integer, BoolExpr> lHFilter, final int numArg,
                          final int numReg) {
        try {
            int size = numArg + numReg + 1; // include return register
            FuncDecl r = this.rPredDef(c, m, pc, size);

            /*Expr[] e = new Expr[4 * size + 5 * this.localHeapSize];
            for (int i = 0, j = size, k = 2 * size, l = 3 * size; i < size; i++, j++, k++, l++) {
                e[i] = rUp.get(i);
                if (e[i] == null) {
                    e[i] = var.getV(i);
                }
                e[j] = rUpHigh.get(i);
                if (e[j] == null) {
                    e[j] = var.getH(i);
                }
                e[k] = rUpLocal.get(i);
                if (e[k] == null) {
                    e[k] = var.getL(i);
                }
                e[l] = rUpGlobal.get(i);
                if (e[l] == null) {
                    e[l] = var.getG(i);
                }
            }
            ;
            for (int loop = 0,  i = 4 * size, j = 4 * size + this.localHeapSize, k = 4 * size
                    + 2 * this.localHeapSize, l = 4 * size + 3 * this.localHeapSize, n = 4 * size
                         + 4 * this.localHeapSize; loop < this.localHeapSize; loop++, i++, j++, k++, l++, n++) {
                e[i] = lHValues.get(loop);
                if (e[i] == null) {
                    e[i] = var.getLHV(loop);
                }
                e[j] = lHHigh.get(loop);
                if (e[j] == null) {
                    e[j] = var.getLHH(loop);
                }
                e[k] = lHLocal.get(loop);
                if (e[k] == null) {
                    e[k] = var.getLHL(loop);
                }
                e[l] = lHGlobal.get(loop);
                if (e[l] == null) {
                    e[l] = var.getLHG(loop);
                }
                e[n] = lHFilter.get(loop);
                if (e[n] == null) {
                    e[n] = var.getLHF(loop);
                }
            }*/

            Expr[] e = getExpressions(rUp,
                    rUpHigh, rUpLocal,
                    rUpGlobal, lHValues,
                    lHHigh, lHLocal,
                    lHGlobal, lHFilter, numArg,
                    numReg);
            ;
            BoolExpr rez = (BoolExpr) r.apply(e);

            return rez;
        } catch (Z3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("FSEngine Failed: rPred");
        }
    }

    public BoolExpr rPredInvok(final String c, final String m, final int pc, final Map<Integer, BitVecExpr> rUp,
                               final Map<Integer, BoolExpr> rUpHigh, final Map<Integer, BoolExpr> rUpLocal,
                               final Map<Integer, BoolExpr> rUpGlobal, final Map<Integer, BitVecExpr> lHValues,
                               final Map<Integer, BoolExpr> lHHigh, final Map<Integer, BoolExpr> lHLocal,
                               final Map<Integer, BoolExpr> lHGlobal, final Map<Integer, BoolExpr> lHFilter, final int numArg,
                               final int numReg, final int size) {
        try {
            int rsize = numArg + numReg + 1; // include return register
            FuncDecl r = this.rPredDef(c, m, pc, rsize);

            Expr[] e = new Expr[4 * rsize + 5 * this.localHeapSize];
            for (int i = 0, j = rsize, k = 2 * rsize, l = 3 * rsize; i < rsize; i++, j++, k++, l++) {
                e[i] = rUp.get(i);
                if (e[i] == null) {
                    e[i] = this.mkBitVector(0, size);
                }
                e[j] = rUpHigh.get(i);
                if (e[j] == null) {
                    e[j] = this.mkFalse();
                }
                e[k] = rUpLocal.get(i);
                if (e[k] == null) {
                    e[k] = this.mkFalse();
                }
                e[l] = rUpGlobal.get(i);
                if (e[l] == null) {
                    e[l] = this.mkFalse();
                }
            }
            ;
            for (int loop = 0, i = 4 * rsize, j = 4 * rsize + this.localHeapSize, k = 4 * rsize
                    + 2 * this.localHeapSize, l = 4 * rsize + 3 * this.localHeapSize, n = 4 * rsize
                         + 4 * this.localHeapSize; loop < this.localHeapSize; loop++, i++, j++, k++, l++, n++) {
                e[i] = lHValues.get(loop);
                if (e[i] == null) {
                    e[i] = var.getLHV(loop);
                }
                e[j] = lHHigh.get(loop);
                if (e[j] == null) {
                    e[j] = var.getLHH(loop);
                }
                e[k] = lHLocal.get(loop);
                if (e[k] == null) {
                    e[k] = var.getLHL(loop);
                }
                e[l] = lHGlobal.get(loop);
                if (e[l] == null) {
                    e[l] = var.getLHG(loop);
                }
                e[n] = this.mkFalse();

            }
            ;

            return (BoolExpr) r.apply(e);
        } catch (Z3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("FSEngine Failed: rPredInvok");
        }
    }

    private FuncDecl resPredDef(String c, String m, int size) {
        try {
            BitVecSort bv64 = mContext.mkBitVecSort(bvSize);
            BoolSort bool = mContext.mkBoolSort();

            String funcName = "RES_" + c + '_' + m;
            Sort[] domains = new Sort[4 * size + 5 * localHeapSize];
            Arrays.fill(domains, 0, size, bv64); // argument + register + result register
            Arrays.fill(domains, size, 4 * size, bool); // high value + local object label + global object label
            Arrays.fill(domains, 4 * size, 4 * size + localHeapSize, bv64);
            // local + heap + entries
            Arrays.fill(domains, 4 * size + localHeapSize, 4 * size + 5 * localHeapSize, bool);
            // high value and local object label and global object label and abstract filter

            FuncDecl f = mContext.mkFuncDecl(funcName, domains, bool);

            this.declareRel(f);
            return f;
        } catch (Z3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("FSEngine Failed: resPredDef");
        }
    }

    public BoolExpr resPred(final String c, final String m, final Map<Integer, BitVecExpr> rUp,
                            final Map<Integer, BoolExpr> rUpHigh, final Map<Integer, BoolExpr> rUpLocal,
                            final Map<Integer, BoolExpr> rUpGlobal, final Map<Integer, BitVecExpr> lHValues,
                            final Map<Integer, BoolExpr> lHHigh, final Map<Integer, BoolExpr> lHLocal,
                            final Map<Integer, BoolExpr> lHGlobal, final Map<Integer, BoolExpr> lHFilter, final int numArg) {
        try {
            int size = numArg + 1; // include return register
            FuncDecl res = this.resPredDef(c, m, size);

            Expr[] e = new Expr[4 * size + 5 * this.localHeapSize];
            for (int i = 0, j = size, k = 2 * size, l = 3 * size; i < size; i++, j++, k++, l++) {
                e[i] = rUp.get(i);
                if (e[i] == null) {
                    e[i] = var.getV(i);
                }
                e[j] = rUpHigh.get(i);
                if (e[j] == null) {
                    e[j] = var.getH(i);
                }
                e[k] = rUpLocal.get(i);
                if (e[k] == null) {
                    e[k] = var.getL(i);
                }
                e[l] = rUpGlobal.get(i);
                if (e[l] == null) {
                    e[l] = var.getG(i);
                }
            }
            ;

            for (int loop = 0, i = 4 * size, j = 4 * size + this.localHeapSize, k = 4 * size
                    + 2 * this.localHeapSize, l = 4 * size + 3 * this.localHeapSize, n = 4 * size
                         + 4 * this.localHeapSize; loop < this.localHeapSize; loop++, i++, j++, k++, l++, n++) {
                e[i] = lHValues.get(loop);
                if (e[i] == null) {
                    e[i] = var.getLHV(loop);
                }
                e[j] = lHHigh.get(loop);
                if (e[j] == null) {
                    e[j] = var.getLHH(loop);
                }
                e[k] = lHLocal.get(loop);
                if (e[k] == null) {
                    e[k] = var.getLHL(loop);
                }
                e[l] = lHGlobal.get(loop);
                if (e[l] == null) {
                    e[l] = var.getLHG(loop);
                }
                e[n] = lHFilter.get(loop);
                if (e[n] == null) {
                    e[n] = var.getLHF(loop);
                }
            }
            ;
            //this.addQuery(new Z3Query((BoolExpr) res.apply(e), c + ' ' + m , true, c, m, "rez", ""));

            return (BoolExpr) res.apply(e);
        } catch (Z3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("FSEngine Failed: resPred");
        }
    }

    public BoolExpr hPred(BitVecExpr cname, BitVecExpr inst, BitVecExpr element, BitVecExpr value, BoolExpr label,
                          BoolExpr block) {
        try {
            return (BoolExpr) func.getH().apply(cname, inst, element, value, label, block);
        } catch (Z3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("FSEngine Failed: hPred");
        }
    }

    public BoolExpr hiPred(BitVecExpr cname, BitVecExpr inst, BitVecExpr value, BoolExpr label, BoolExpr block) {
        try {
            return (BoolExpr) func.getHi().apply(cname, inst, value, label, block);
        } catch (Z3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("FSEngine Failed: hiPred");
        }
    }

    public BoolExpr iPred(BitVecExpr cname, BitVecExpr inst, BitVecExpr value, BoolExpr label, BoolExpr block) {
        try {
            return (BoolExpr) func.getI().apply(cname, inst, value, label, block);
        } catch (Z3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("FSEngine Failed: iPred");
        }
    }

    public BoolExpr sPred(IntExpr v1, IntExpr v2, BitVecExpr v3, BoolExpr v4, BoolExpr v5) {
        try {
            return (BoolExpr) func.getS().apply(v1, v2, v3, v4, v5);
        } catch (Z3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("FSEngine Failed: sPred");
        }
    }

    public BoolExpr taintPred(BitVecExpr value, BoolExpr label) {
        try {
            return (BoolExpr) func.getTaint().apply(value, label);
        } catch (Z3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Z3Engine Failed: taintPred");
        }
    }

    /*
     * Declare the type of the ReachLH predicate.
     * ReachLH(v_1,v_2,h^*) means that starting from location v_1, one can reach location v_2 in the local heap h^*
     */
    private FuncDecl reachLHDef() {
        if (!isInitialized()){
            throw new RuntimeException("Initialize the FSEngine before defining ReachLH predicate");
        }
        try {
            BitVecSort bv64 = mContext.mkBitVecSort(bvSize);
            BoolSort bool = mContext.mkBoolSort();

            String funcName = "ReachLH";
            Sort[] domains = new Sort[2 + 2 * localHeapSize];
            // location v_1 (starting point)
            Arrays.fill(domains, 0, 1, bv64);
            // location v_2 (reachable point)
            Arrays.fill(domains, 1, 2, bv64);
            // local heap entries
            Arrays.fill(domains, 2, 2 + localHeapSize, bv64);
            // local object labels
            Arrays.fill(domains, 2 + localHeapSize, 2 + 2 * localHeapSize, bool);
            FuncDecl f = mContext.mkFuncDecl(funcName, domains, mContext.mkBoolSort());
            this.declareRel(f);
            return f;
        } catch (Z3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("FSEngine Failed: reachLHDef");
        }
    }


    public BoolExpr reachLHPred(BitVecExpr vi, BitVecExpr vr, final Map<Integer, BitVecExpr> lHValues, final Map<Integer, BoolExpr> lHLocal) {
        try {
            FuncDecl rlh = func.getReachLH();

            Expr[] e = new Expr[2 + 2 * this.localHeapSize];
            e[0] = vi;
            e[1] = vr;
            for (int loop = 0, i = 2, j = 2 + this.localHeapSize; loop < this.localHeapSize; loop++, i++, j++) {
                e[i] = lHValues.get(loop);
                if (e[i] == null) {
                    e[i] = var.getLHV(loop);
                }
                e[j] = lHLocal.get(loop);
                if (e[j] == null) {
                    e[j] = var.getLHL(loop);
                }
            }

            return (BoolExpr) rlh.apply(e);
        } catch (Z3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("FSEngine Failed: reachLHPred");
        }
    }


    /*
     * Declare the type of the CFilter predicate.
     * CFilter(v,h^*,k^*) means that starting from location v, one can reach in the local heap h^* all locations marked by a 1 in k^*.
     * Positions description:
     * 1: value of v
     * 2: boolean indicating whether v is a local pointer
     * 2+1 --> 2+localHeapSize: values in the local heap
     * 2+localHeapSize + 1 --> 2 + 2 * localHeapSize: booleans storing local pointer information if the local heap
     * 2+ 2 * localHeapSize + 1 --> 2 + 3 * localHeapSize: k^*
     */
    private FuncDecl cFilterDef() {
        if (!isInitialized()){
            throw new RuntimeException("Initialize the FSEngine before defining ReachLH predicate");
        }
        try {
            BitVecSort bv64 = mContext.mkBitVecSort(bvSize);
            BoolSort bool = mContext.mkBoolSort();

            String funcName = "CFilter";
            Sort[] domains = new Sort[2 + 3 * localHeapSize];
            // value of v
            Arrays.fill(domains, 0, 1, bv64);
            // local label of v
            Arrays.fill(domains, 1, 2, bool);
            // local heap entries
            Arrays.fill(domains, 2, 2 + localHeapSize, bv64);
            // local heap local labels
            Arrays.fill(domains, 2 + localHeapSize, 2 + 2 * localHeapSize, bool);
            // abstract filter labels
            Arrays.fill(domains, 2 + 2 * localHeapSize, 2 + 3 * localHeapSize, bool);
            FuncDecl f = mContext.mkFuncDecl(funcName, domains, mContext.mkBoolSort());
            this.declareRel(f);
            return f;
        } catch (Z3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("FSEngine Failed: cFilterDef");
        }
    }

    /*
     * Warning: default for 'lHFilter' is fsvar.getLHCF
     */
    public BoolExpr cFilterPred(BitVecExpr v, BoolExpr b, final Map<Integer, BitVecExpr> lHValues, final Map<Integer, BoolExpr> lHLocal, final Map<Integer, BoolExpr> lHFilter) {
        try {
            FuncDecl rlh = func.getCFilter();

            Expr[] e = new Expr[2 + 3 * this.localHeapSize];
            e[0] = v;
            e[1] = b;
            for (int loop = 0, i = 2, j = 2 + this.localHeapSize, k = 2 + 2 * this.localHeapSize; loop < this.localHeapSize; loop++, i++, j++, k++) {
                e[i] = lHValues.get(loop);
                if (e[i] == null) {
                    e[i] = var.getLHV(loop);
                }
                e[j] = lHLocal.get(loop);
                if (e[j] == null) {
                    e[j] = var.getLHL(loop);
                }
                e[k] = lHFilter.get(loop);
                if (e[k] == null) {
                    e[k] = var.getLHF(loop);
                }
            }

            return (BoolExpr) rlh.apply(e);
        } catch (Z3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("FSEngine Failed: cFilterPred");
        }
    }

    /*
     * Declare the type of the LiftLH predicate.
     * LiftLH(h^*,k^*) means that the part of the local heap h^* defined by k^* should be lifted
     * Positions description, by block of localHeapSize entries:
     * h^* values
     * h^* high or low label
     * h^* local labels
     * h^* global labels
     * k^*
     */
    /*private FuncDecl liftLHDef() {
    	if (!isInitialized()){
    		throw new RuntimeException("Initialize the FSEngine before defining ReachLH predicate");
    	}
        try {
            BitVecSort bv64 = mContext.mkBitVecSort(bvSize);
            BoolSort bool = mContext.mkBoolSort();

            String funcName = "LiftLH";
            Sort[] domains = new Sort[5 * localHeapSize];
            Arrays.fill(domains, 0, localHeapSize, bv64);
            Arrays.fill(domains, localHeapSize, 5 * localHeapSize, bool);
            FuncDecl f = mContext.mkFuncDecl(funcName, domains, mContext.mkBoolSort());
            this.declareRel(f);
            return f;
        } catch (Z3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("FSEngine Failed: liftLHDef");
        }
    }

    public BoolExpr liftLHPred(final Map<Integer, BitVecExpr> lHValues,  final Map<Integer, BoolExpr> lHHigh, final Map<Integer, BoolExpr> lHLocal,  final Map<Integer, BoolExpr> lHGlobal, final Map<Integer, BoolExpr> lHFilter) {
        try {
            FuncDecl llh= func.getLiftLH();

            Expr[] e = new Expr[5 * this.localHeapSize];
            for (int i = 0; i < this.localHeapSize; i++) {
                e[i] = lHValues.get(i);
                if (e[i] == null) {
                    e[i] = var.getLHV(i);
                }
                e[localHeapSize + i] = lHHigh.get(i);
                if (e[localHeapSize + i] == null) {
                    e[localHeapSize + i] = var.getLHH(i);
                }
                e[2 * localHeapSize + i] = lHLocal.get(i);
                if (e[2 * localHeapSize + i] == null) {
                    e[2 * localHeapSize + i] = var.getLHL(i);
                }
                e[3 * localHeapSize + i] = lHGlobal.get(i);
                if (e[3 * localHeapSize + i] == null) {
                    e[3 * localHeapSize + i] = var.getLHG(i);
                }
                e[4 * localHeapSize + i] = lHFilter.get(i);
                if (e[4 * localHeapSize + i] == null) {
                    e[4 * localHeapSize + i] = var.getLHF(i);
                }
            }

            return (BoolExpr) llh.apply(e);
        } catch (Z3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("FSEngine Failed: liftLHPred");
        }
    }*/


    public BoolExpr reachPred(BitVecExpr value, BitVecExpr value2) {
        try {
            return (BoolExpr) func.getReach().apply(value, value2);
        } catch (Z3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Z3Engine Failed: reachPred");
        }
    }
    public BoolExpr joinPred(BitVecExpr value, BoolExpr value2) {
        try {
            return (BoolExpr) func.getJoin().apply(value, value2);
        } catch (Z3Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Z3Engine Failed: reachPredP");
        }
    }
}
