// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.psi.impl.compiled;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.roots.FileIndexFacade;
import com.intellij.openapi.util.AtomicNotNullLazyValue;
import com.intellij.openapi.util.NotNullLazyValue;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.impl.ElementPresentationUtil;
import com.intellij.psi.impl.cache.TypeInfo;
import com.intellij.psi.impl.java.stubs.JavaStubElementTypes;
import com.intellij.psi.impl.java.stubs.PsiParameterStub;
import com.intellij.psi.impl.java.stubs.impl.PsiParameterStubImpl;
import com.intellij.psi.impl.source.SourceTreeToPsiMap;
import com.intellij.psi.impl.source.tree.TreeElement;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.stubs.StubElement;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.ui.IconManager;
import com.intellij.ui.icons.RowIcon;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.PlatformIcons;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public class ClsParameterImpl extends ClsRepositoryPsiElement<PsiParameterStub> implements PsiParameter {
  private final NotNullLazyValue<PsiTypeElement> myType;
  private volatile String myMirrorName;

  public ClsParameterImpl(@NotNull PsiParameterStub stub) {
    super(stub);
    myType = new AtomicNotNullLazyValue<PsiTypeElement>() {
      @NotNull
      @Override
      protected PsiTypeElement compute() {
        PsiParameterStub stub = getStub();
        String typeText = TypeInfo.createTypeText(stub.getType(false));
        assert typeText != null : stub;
        return new ClsTypeElementImpl(ClsParameterImpl.this, typeText, ClsTypeElementImpl.VARIANCE_NONE);
      }
    };
  }

  @Override
  public PsiIdentifier getNameIdentifier() {
    return null;
  }

  @NotNull
  @Override
  public String getName() {
    return CachedValuesManager.getCachedValue(this, () -> CachedValueProvider.Result.create(calcName(),
                                                                                            getContainingFile(),
                                                                                            getContainingFile().getNavigationElement(),
                                                                                            FileIndexFacade.getInstance(getProject()).getRootModificationTracker(),
                                                                                            DumbService.getInstance(getProject()).getModificationTracker()));
  }

  @NotNull
  private String calcName() {
    PsiParameterStubImpl parameterStub = (PsiParameterStubImpl)getStub();
    if (!parameterStub.isAutoGeneratedName()) {
      return parameterStub.getName();
    }

    if (DumbService.getInstance(getProject()).isDumb()) {
      return "p";
    }

    ClsMethodImpl method = (ClsMethodImpl)getDeclarationScope();
    PsiMethod sourceMethod = method.getSourceMirrorMethod();
    if (sourceMethod != null) {
      assert sourceMethod != method : method;
      return sourceMethod.getParameterList().getParameters()[getIndex()].getName();
    }

    return getMirrorName();
  }

  public boolean isAutoGeneratedName() {
    return ((PsiParameterStubImpl)getStub()).isAutoGeneratedName() &&
           !DumbService.getInstance(getProject()).isDumb() &&
           ((ClsMethodImpl)getDeclarationScope()).getSourceMirrorMethod() == null;
  }

  @Override
  public PsiElement setName(@NotNull String name) throws IncorrectOperationException {
    throw cannotModifyException(this);
  }

  @Override
  @NotNull
  public PsiTypeElement getTypeElement() {
    return myType.getValue();
  }

  @Override
  @NotNull
  public PsiType getType() {
    return getTypeElement().getType();
  }

  @Override
  @NotNull
  public PsiModifierList getModifierList() {
    final StubElement<PsiModifierList> child = getStub().findChildStubByType(JavaStubElementTypes.MODIFIER_LIST);
    assert child != null;
    return child.getPsi();
  }

  @Override
  public boolean hasModifierProperty(@NotNull String name) {
    return getModifierList().hasModifierProperty(name);
  }

  @Override
  public PsiExpression getInitializer() {
    return null;
  }

  @Override
  public boolean hasInitializer() {
    return false;
  }

  @Override
  public Object computeConstantValue() {
    return null;
  }

  @Override
  public void normalizeDeclaration() throws IncorrectOperationException {
  }

  @Override
  public void appendMirrorText(int indentLevel, @NotNull StringBuilder buffer) {
    PsiAnnotation[] annotations = getModifierList().getAnnotations();
    for (PsiAnnotation annotation : annotations) {
      appendText(annotation, indentLevel, buffer);
      buffer.append(' ');
    }
    appendText(getTypeElement(), indentLevel, buffer, " ");
    buffer.append(getMirrorName());
  }

  @NotNull
  private String getMirrorName() {
    String mirrorName = myMirrorName;
    if (mirrorName == null) {
      // parameter name may depend on a name of a previous one in a same parameter list
      synchronized (getParent()) {
        mirrorName = myMirrorName;
        if (mirrorName == null) {
          myMirrorName = mirrorName = calcNiceParameterName();
        }
      }
    }
    return mirrorName;
  }

  @NotNull
  private String calcNiceParameterName() {
    String name = null;

    PsiParameterStubImpl stub = (PsiParameterStubImpl)getStub();
    if (!stub.isAutoGeneratedName() || DumbService.getInstance(getProject()).isDumb()) {
      name = stub.getName();
    }

    if (name == null) {
      name = "p";

      JavaCodeStyleManager codeStyleManager = JavaCodeStyleManager.getInstance(getProject());
      String[] nameSuggestions = codeStyleManager.suggestCompiledParameterName(getType()).names;
      if (nameSuggestions.length > 0 && nameSuggestions[0] != null) {
        name = nameSuggestions[0];
      }

      String base = name;
      int n = 0;
      AttemptsLoop:
      while (true) {
        for (PsiParameter parameter : ((PsiParameterList)getParent()).getParameters()) {
          if (parameter == this) break AttemptsLoop;
          String prevName = ((ClsParameterImpl)parameter).getMirrorName();
          if (name.equals(prevName)) {
            name = base + (++n);
            continue AttemptsLoop;
          }
        }
      }
    }

    return name;
  }

  @Override
  public void setMirror(@NotNull TreeElement element) throws InvalidMirrorException {
    setMirrorCheckingType(element, null);

    PsiParameter mirror = SourceTreeToPsiMap.treeToPsiNotNull(element);
    setMirror(getModifierList(), mirror.getModifierList());
    setMirror(getTypeElement(), mirror.getTypeElement());
  }

  @Override
  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof JavaElementVisitor) {
      ((JavaElementVisitor)visitor).visitParameter(this);
    }
    else {
      visitor.visitElement(this);
    }
  }

  @Override
  @NotNull
  public PsiElement getDeclarationScope() {
    // only method parameters exist in compiled code
    return getParent().getParent();
  }

  private int getIndex() {
    final PsiParameterStub stub = getStub();
    return stub.getParentStub().getChildrenStubs().indexOf(stub);
  }

  @Override
  public boolean isVarArgs() {
    final PsiParameterList paramList = (PsiParameterList)getParent();
    final PsiMethod method = (PsiMethod)paramList.getParent();
    return method.isVarArgs() && getIndex() == paramList.getParametersCount() - 1;
  }

  @Override
  protected boolean isVisibilitySupported() {
    return true;
  }

  @Override
  public Icon getElementIcon(final int flags) {
    final RowIcon baseIcon = IconManager.getInstance().createLayeredIcon(this, PlatformIcons.PARAMETER_ICON, 0);
    return ElementPresentationUtil.addVisibilityIcon(this, flags, baseIcon);
  }

  @Override
  @NotNull
  public SearchScope getUseScope() {
    return new LocalSearchScope(getDeclarationScope());
  }

  @Override
  public String toString() {
    return "PsiParameter";
  }
}
