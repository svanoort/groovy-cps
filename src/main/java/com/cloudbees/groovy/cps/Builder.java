package com.cloudbees.groovy.cps;

import com.cloudbees.groovy.cps.impl.Constant;
import org.codehaus.groovy.runtime.ScriptBytecodeAdapter;
import org.codehaus.groovy.runtime.callsite.CallSite;
import org.codehaus.groovy.runtime.callsite.CallSiteArray;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Kohsuke Kawaguchi
 */
public class Builder {
    private static final Expression NOOP = new Constant(null);

    public Expression noop() {
        return NOOP;
    }

    public Expression constant(Object o) {
        return new Constant(o);
    }

    public Expression sequence(Expression... bodies) {
        if (bodies.length==0)   return NOOP;

        Expression e = bodies[0];
        for (int i=1; i<bodies.length; i++)
            e = sequence(e,bodies[i]);
        return e;
    }

    public Expression sequence(final Expression exp1, final Expression exp2) {
        return new Expression() {
            public Next eval(Env e, final Continuation k) {
                return new Next(exp1,e,new Continuation() {
                    public Next receive(Env e, Object _) {
                        return new Next(exp2,e,k);
                    }
                });
            }
        };
    }

//    public Expression compareLessThan(final Expression lhs, final Expression rhs) {
//
//    }

    public Expression getLocalVariable(final String name) {
        return new Expression() {
            public Next eval(Env e, Continuation k) {
                return k.receive(e, e.get(name));
            }
        };
    }

    public Expression setLocalVariable(final String name, final Expression rhs) {
        return new Expression() {
            public Next eval(final Env e, final Continuation k) {
                return rhs.eval(e,new Continuation() {
                    public Next receive(Env _, Object o) {
                        e.set(name,o);
                        return k.receive(_,o);
                    }
                });
            }
        };
    }

    /**
     * if (...) { ... } else { ... }
     */
    public Expression _if(final Expression cond, final Expression then, final Expression els) {
        return new Expression() {
            public Next eval(final Env e, final Continuation k) {
                return cond.eval(e,new Continuation() {
                    public Next receive(Env _, Object o) {
                        return (asBoolean(o) ? then : els).eval(e,k);
                    }
                });
            }
        };
    }

    /**
     * for (e1; e2; e3) { ... }
     */
    public Expression _for(final Expression e1, final Expression e2, final Expression e3, final Expression body) {
        return new Expression() {
            public Next eval(Env _e, final Continuation loopEnd) {
                final Env e = _e.newBlockScope();   // a for-loop creates a new scope for variables declared in e1,e2, & e3

                final Continuation loopHead = new Continuation() {
                    final Continuation _loopHead = this;    // because 'loopHead' cannot be referenced from within the definition

                    public Next receive(Env _, Object __) {
                        return new Next(e2,e,new Continuation() {// evaluate e2
                            public Next receive(Env _, Object v2) {
                                if (asBoolean(v2)) {
                                    // loop
                                    return new Next(body,e,new Continuation() {
                                        public Next receive(Env _, Object o) {
                                            return new Next(e3,e,_loopHead);
                                        }
                                    });
                                } else {
                                    // exit loop
                                    return loopEnd.receive(_,null);
                                }
                            }
                        });
                    }
                };

                return e1.eval(e,loopHead);
            }
        };
    }


    private boolean asBoolean(Object o) {
        try {
            return (Boolean)ScriptBytecodeAdapter.asType(o,Boolean.class);
        } catch (Throwable e) {
            // TODO: exception handling
            e.printStackTrace();
            return false;
        }
    }

    /**
     * LHS.name(...)
     */
    public Expression functionCall(final Expression lhs, final String name, Expression... argExps) {
        final CallSite callSite = fakeCallSite(name);
        final Expression args = evalArgs(argExps);

        return new Expression() {
            public Next eval(final Env e, final Continuation k) {
                return new Next(lhs,e, new Continuation() {// evaluate lhs
                    public Next receive(Env _, final Object lhs) {
                        return args.eval(e,new Continuation() {
                            public Next receive(Env _, Object _args) {
                                List args = (List) _args;

                                Object v;
                                try {
                                    v = callSite.call(lhs, args.toArray(new Object[args.size()]));
                                } catch (Throwable t) {
                                    throw new UnsupportedOperationException(t);     // TODO: exception handling
                                }

                                if (v instanceof Function) {
                                    // if this is a workflow function, it'd return a Function object instead
                                    // of actually executing the function, so execute it in the CPS
                                    return ((Function)v).invoke(args,k);
                                } else {
                                    // if this was a normal function, the method had just executed synchronously
                                    return k.receive(e,v);
                                }
                            }
                        });
                    }
                });
            }
        };
    }

    /**
     * name(...)
     */
    public Expression functionCall(final String name, Expression... argExps) {
        final Expression args = evalArgs(argExps);
        return new Expression() {
            public Next eval(final Env e, final Continuation k) {
                // TODO: does this happen before or after the arguments are evaluated?
                final Function f = e.resolveFunction(name);

                return args.eval(e,new Continuation() {
                    public Next receive(Env _, Object args) {
                        return f.invoke((List)args,k);
                    }
                });
            }
        };
    }

    /**
     * Returns an expression that evaluates all the arguments and return it as a {@link List}.
     */
    private Expression evalArgs(final Expression... argExps) {
        return new Expression() {
            public Next eval(Env e, final Continuation k) {
                final List<Object> args = new ArrayList<Object>(argExps.length); // this is where we build up actual arguments

                Next n = null;
                for (int i = argExps.length - 1; i >= 0; i--) {
                    final Next nn = n;
                    n = new Next(argExps[i], e, new Continuation() {
                        public Next receive(Env e, Object o) {
                            args.add(o);
                            if (nn != null)
                                return nn;
                            else
                                return k.receive(e,args);
                        }
                    });
                }
                return n;
            }
        };
    }

    /*TODO: specify the proper owner value (to the script that includes the call site) */
    private static CallSite fakeCallSite(String method) {
        CallSiteArray csa = new CallSiteArray(Builder.class, new String[]{method});
        return csa.array[0];
    }
}