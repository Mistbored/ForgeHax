package dev.fiki.forgehax.asm.utils.transforming;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import dev.fiki.forgehax.api.mapper.ClassMapping;
import dev.fiki.forgehax.api.mapper.FieldMapping;
import dev.fiki.forgehax.api.mapper.MethodMapping;
import dev.fiki.forgehax.asm.ASMCommon;
import dev.fiki.forgehax.asm.ForgeHaxCoreTransformer;
import dev.fiki.forgehax.asm.utils.asmtype.ASMClass;
import dev.fiki.forgehax.asm.utils.asmtype.ASMField;
import dev.fiki.forgehax.asm.utils.asmtype.ASMMethod;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import javax.annotation.Nonnull;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static dev.fiki.forgehax.asm.ASMCommon.getLogger;

@Getter
public class PatchScanner implements ASMCommon {
  private final List<ITransformer<?>> transformers = new ArrayList<>();

  @SneakyThrows
  public PatchScanner(ForgeHaxCoreTransformer core, Patch patch) {
    // collect all the method transformers and create a transformer object to wrap the method call
    for (Method method : patch.getClass().getMethods()) {
      if (method.isAnnotationPresent(Inject.class) && method.isAnnotationPresent(MethodMapping.class)) {

        ConditionalInject condition = method.getAnnotation(ConditionalInject.class);

        if (condition != null) {
          boolean not = condition.value().startsWith("!");
          String serviceName = not ? condition.value().substring(1) : condition.value();
          boolean exists = core.getOtherServices().contains(serviceName);

          if (not && exists) {
            getLogger().info("Skipping {} because service {} is present", method.getName(), serviceName);
            continue;
          } else if (!not && !exists) {
            getLogger().info("Skipping {} because service {} is not present", method.getName(), serviceName);
            continue;
          }
        }

        transformers.add(new InternalMethodTransformer(patch, method,
            ASMMethod.unmap(method.getAnnotation(MethodMapping.class))));
      }
    }
  }

  private static Object getMappedType(Class<?> type, AnnotatedElement e) {
    if (ASMClass.class.isAssignableFrom(type)) {
      if (e.isAnnotationPresent(ClassMapping.class)) {
        return ASMClass.unmap(e.getAnnotation(ClassMapping.class));
      } else {
        throw new Error("ASMClass parameter must have a ClassMapping annotation");
      }
    } else if (ASMField.class.isAssignableFrom(type)) {
      if (e.isAnnotationPresent(FieldMapping.class)) {
        return ASMField.unmap(e.getAnnotation(FieldMapping.class));
      } else {
        throw new Error("ASMField parameter must have a MethodMapping annotation");
      }
    } else if (ASMMethod.class.isAssignableFrom(type)) {
      if (e.isAnnotationPresent(MethodMapping.class)) {
        return ASMMethod.unmap(e.getAnnotation(MethodMapping.class));
      } else {
        throw new Error("ASMMethod parameter must have a MethodMapping annotation");
      }
    }
    return null;
  }

  private static boolean addMappedArgument(Class<?> type, AnnotatedElement e, List<Object> arguments) {
    Object o = getMappedType(type, e);
    return o != null && arguments.add(o);
  }

  @Getter
  @RequiredArgsConstructor
  static class InternalMethodTransformer implements ITransformer<ClassNode> {
    private final Object parent;
    private final Method method;
    private final ASMMethod targetMethod;

    @Nonnull
    @Override
    @SneakyThrows
    public ClassNode transform(ClassNode input, ITransformerVotingContext context) {
      for (MethodNode node : input.methods) {
        if (getTargetMethod().isNameEqual(node.name) && getTargetMethod().isDescriptorEqual(node.desc)) {
          // build the list of arguments
          List<Object> arguments = new ArrayList<>();
          for (Parameter parameter : getMethod().getParameters()) {
            Class<?> type = parameter.getType();

            if (MethodNode.class.isAssignableFrom(type)) {
              arguments.add(node);
            } else if (!addMappedArgument(type, parameter, arguments)) {
              throw new Error("Unknown parameter type " + type.getName());
            }
          }

          getLogger().debug("Attempting to transform method {}", getTargetMethod());

          try {
            method.invoke(parent, arguments.toArray());
            return input;
          } catch (Throwable t) {
            if (t instanceof InvocationTargetException) {
              t = t.getCause();
            }

            getLogger().error("Failed to transform method {}!", getTargetMethod());
            getLogger().error(t, t);
          }
        }
      }

      getLogger().error("Could not find and transform method {}!", getTargetMethod());

      return input;
    }

    @Nonnull
    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
      return TransformerVoteResult.YES;
    }

    @Nonnull
    @Override
    public Set<Target> targets() {
      return Collections.singleton(Target.targetClass(getTargetMethod().getParentClass().getName()));
    }

    @Override
    public String toString() {
      return getTargetMethod().toString();
    }
  }
}
