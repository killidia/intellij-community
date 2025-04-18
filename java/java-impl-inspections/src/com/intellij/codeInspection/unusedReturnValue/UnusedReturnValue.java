// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInspection.unusedReturnValue;

import com.intellij.analysis.AnalysisScope;
import com.intellij.codeInsight.options.JavaInspectionControls;
import com.intellij.codeInspection.*;
import com.intellij.codeInspection.deadCode.UnreferencedFilter;
import com.intellij.codeInspection.options.OptPane;
import com.intellij.codeInspection.reference.*;
import com.intellij.java.JavaBundle;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.AccessModifier;
import com.intellij.psi.util.PropertyUtilBase;
import com.siyeh.ig.psiutils.MethodUtils;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public final class UnusedReturnValue extends GlobalJavaBatchInspectionTool{
  public boolean IGNORE_BUILDER_PATTERN;
  public static final AccessModifier DEFAULT_HIGHEST_MODIFIER = AccessModifier.PUBLIC;
  public AccessModifier highestModifier = DEFAULT_HIGHEST_MODIFIER;
  
  @NotNull AccessModifier getHighestModifier() {
    return Objects.requireNonNullElse(highestModifier, DEFAULT_HIGHEST_MODIFIER);
  }

  @Override
  public CommonProblemDescriptor @Nullable [] checkElement(@NotNull RefEntity refEntity,
                                                           @NotNull AnalysisScope scope,
                                                           @NotNull InspectionManager manager,
                                                           @NotNull GlobalInspectionContext globalContext,
                                                           @NotNull ProblemDescriptionsProcessor processor) {
    if (refEntity instanceof RefMethod refMethod) {
      if (Objects.requireNonNull(AccessModifier.fromPsiModifier(refMethod.getAccessModifier())).compareTo(getHighestModifier()) < 0 ||
          refMethod.isConstructor() ||
          !refMethod.getSuperMethods().isEmpty() ||
          refMethod.getInReferences().isEmpty() ||
          refMethod.isEntry() ||
          refMethod.isReturnValueUsed() ||
          UnreferencedFilter.isExternallyReferenced(refMethod)) {
        return null;
      }

      final PsiMethod psiMethod = refMethod.getUastElement().getJavaPsi();
      if (IGNORE_BUILDER_PATTERN && (PropertyUtilBase.isSimplePropertySetter(psiMethod)) || MethodUtils.isChainable(psiMethod)) return null;
      final boolean isNative = psiMethod.hasModifierProperty(PsiModifier.NATIVE);
      if (refMethod.isExternalOverride() && !isNative) return null;
      if (RefUtil.isImplicitRead(psiMethod)) return null;
      if (MethodUtils.hasCanIgnoreReturnValueAnnotation(psiMethod, psiMethod.getContainingFile())) return null;
      return new ProblemDescriptor[]{createProblemDescriptor(psiMethod, manager, processor, isNative, false)};
    }
    return null;
  }

  @Override
  public void writeSettings(@NotNull Element node) throws WriteExternalException {
    if (IGNORE_BUILDER_PATTERN || highestModifier != DEFAULT_HIGHEST_MODIFIER) {
      super.writeSettings(node);
    }
  }

  @Override
  public @NotNull OptPane getOptionsPane() {
    return OptPane.pane(
      OptPane.checkbox("IGNORE_BUILDER_PATTERN", JavaBundle.message("checkbox.ignore.chains")),
      JavaInspectionControls.visibilityChooser("highestModifier", JavaBundle.message("label.maximal.reported.method.visibility"))
    );
  }

  @Override
  protected boolean queryExternalUsagesRequests(final @NotNull RefManager manager,
                                                final @NotNull GlobalJavaInspectionContext globalContext,
                                                final @NotNull ProblemDescriptionsProcessor processor) {
    manager.iterate(new RefJavaVisitor() {
      @Override
      public void visitMethod(final @NotNull RefMethod refMethod) {
        if (processor.getDescriptions(refMethod) == null) return;
        if (PsiModifier.PRIVATE.equals(refMethod.getAccessModifier())) return;
        globalContext.enqueueMethodUsagesProcessor(refMethod, new GlobalJavaInspectionContext.UsagesProcessor() {
          @Override
          public boolean process(PsiReference psiReference) {
            processor.ignoreElement(refMethod);
            return false;
          }
        });
      }
    });

    return false;
  }

  @Override
  public @NotNull String getGroupDisplayName() {
    return InspectionsBundle.message("group.names.declaration.redundancy");
  }

  @Override
  public @NotNull String getShortName() {
    return "UnusedReturnValue";
  }

  @Override
  public @Nullable QuickFix getQuickFix(String hint) {
    return new MakeVoidQuickFix(null);
  }

  @Override
  public @Nullable LocalInspectionTool getSharedLocalInspectionTool() {
    return new UnusedReturnValueLocalInspection(this);
  }


  static ProblemDescriptor createProblemDescriptor(@NotNull PsiMethod psiMethod,
                                                   @NotNull InspectionManager manager,
                                                   @Nullable ProblemDescriptionsProcessor processor,
                                                   boolean isNative, boolean isOnTheFly) {
    return manager.createProblemDescriptor(psiMethod.getNameIdentifier(),
                                           JavaBundle.message("inspection.unused.return.value.problem.descriptor"),
                                           isNative ? null : new MakeVoidQuickFix(processor),
                                           ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                                           isOnTheFly);
  }
}
