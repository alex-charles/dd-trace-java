package datadog.trace.instrumentation.hibernate.core.v3_3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.context.ContextStoreDef;
import datadog.trace.agent.tooling.context.ContextStoreMapping;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.instrumentation.hibernate.SessionMethodUtils;
import datadog.trace.instrumentation.hibernate.SessionState;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;
import org.hibernate.Criteria;

@AutoService(Instrumenter.class)
@ContextStoreDef({
  @ContextStoreMapping(
      keyClass = "org.hibernate.Criteria",
      contextClass = "datadog.trace.instrumentation.hibernate.SessionState")
})
public class CriteriaInstrumentation extends AbstractHibernateInstrumentation {
  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("org.hibernate.Criteria"));
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod().and(namedOneOf("list", "uniqueResult", "scroll")),
        CriteriaInstrumentation.class.getName() + "$CriteriaMethodAdvice");
  }

  public static class CriteriaMethodAdvice extends V3Advice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static SessionState startMethod(
        @Advice.This final Criteria criteria, @Advice.Origin("#m") final String name) {

      final ContextStore<Criteria, SessionState> contextStore =
          InstrumentationContext.get(Criteria.class, SessionState.class);

      return SessionMethodUtils.startScopeFrom(
          contextStore, criteria, "hibernate.criteria." + name, null, true);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void endMethod(
        @Advice.Enter final SessionState state,
        @Advice.Thrown final Throwable throwable,
        @Advice.Return(typing = Assigner.Typing.DYNAMIC) final Object entity) {

      SessionMethodUtils.closeScope(state, throwable, entity, true);
    }
  }
}
