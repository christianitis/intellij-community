// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.util.treeView;

import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Ref;
import com.intellij.ui.TreeUIHelper;
import com.intellij.ui.speedSearch.ElementFilter;
import com.intellij.ui.treeStructure.*;
import com.intellij.ui.treeStructure.filtered.FilteringTreeBuilder;
import com.intellij.ui.treeStructure.filtered.FilteringTreeStructure;
import com.intellij.util.ui.tree.TreeUtilTest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.concurrency.Promise;

import java.util.LinkedHashMap;

/**
 * @author Kirill Kalishev
 * @author Konstantin Bulenkov
 */
public class FilteringTreeBuilderTest extends BaseTreeTestCase  {
  private FilteringTreeBuilder myBuilder;
  private MyFilter myFilter;
  private Node myRoot;
  private SimpleTreeStructure myStructure;

  public FilteringTreeBuilderTest() {
    super(false, false);
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    myTree = new SimpleTree() {
      @Override
      protected void configureUiHelper(final TreeUIHelper helper) {
      }
    };

    myFilter = new MyFilter();
    myRoot = new Node(null, "/");
    myStructure = new SimpleTreeStructure.Impl(myRoot);
  }

  private void initBuilder() throws Exception {
    myBuilder = new FilteringTreeBuilder(myTree, myFilter, myStructure, AlphaComparator.INSTANCE) {
      @Override
      protected AbstractTreeUpdater createUpdater() {
       return _createUpdater(this);
      }
    };

    showTree();

    Disposer.register(getRootDisposable(), myBuilder);
  }

  public void testFilter() {
    TreeUtilTest.waitForTestOnEDT(() -> {
      try {
        implFilter();
      }
      catch (Exception exception) {
        throw new IllegalStateException(exception);
      }
    });
  }

  private void implFilter() throws Exception {
    myTree.setRootVisible(false);

    final Node f1 = myRoot.addChild("folder1");
    f1.addChild("file11");
    f1.addChild("file12");
    Node f11 = f1.addChild("folder11");
    f11.addChild("element111");
    myRoot.addChild("folder2").addChild("file21");

    initBuilder();

    assertTree("""
                 -/
                  -folder1
                   file11
                   file12
                   -folder11
                    element111
                  -folder2
                   file21
                 """);

    update("", findNode("file11"));
    assertTree("""
                 -/
                  -folder1
                   [file11]
                   file12
                   -folder11
                    element111
                  -folder2
                   file21
                 """);

    updateFilter("f");
    assertTree("""
                 -/
                  -folder1
                   [file11]
                   file12
                   folder11
                  -folder2
                   file21
                 """);

    updateFilter("fo");
    assertTree("""
                 -/
                  -folder1
                   [folder11]
                  folder2
                 """);

    updateFilter("fo_");
    assertTree("/\n");

    updateFilter("");
    assertTree("""
                 -/
                  -[folder1]
                   file11
                   file12
                   -folder11
                    element111
                  -folder2
                   file21
                 """);


    select("element111");
    assertTree("""
                 -/
                  -folder1
                   file11
                   file12
                   -folder11
                    [element111]
                  -folder2
                   file21
                 """);

    updateFilter("folder2");
    assertTree("""
                 -/
                  [folder2]
                 """);

    updateFilter("");
    assertTree("""
                 -/
                  -folder1
                   file11
                   file12
                   -folder11
                    element111
                  -[folder2]
                   file21
                 """);

    updateFilter("file1");
    assertTree("""
                 -/
                  -[folder1]
                   file11
                   file12
                 """);

    select("file12");
    assertTree("""
                 -/
                  -folder1
                   file11
                   [file12]
                 """);

    updateFilter("");
    assertTree("""
                 -/
                  -folder1
                   file11
                   [file12]
                   -folder11
                    element111
                  -folder2
                   file21
                 """);

  }

  private void select(String element) throws Exception {
    FilteringTreeStructure.FilteringNode node = myBuilder.getVisibleNodeFor(findNode(element));
    select(new Object[] {node}, false);
  }

  private void updateFilter(final String text) {
     update(text, null);
   }

  private void update(final String text, @Nullable final Object selection) {
    myFilter.update(text, selection);
  }

  private static final class Node extends CachingSimpleNode {

    private final LinkedHashMap<String, Node> myKids = new LinkedHashMap<>();

    private Node(final SimpleNode aParent, String name) {
      super(aParent);
      myName = name;
    }

    public Node addChild(String name) {
      if (!myKids.containsKey(name)) {
        myKids.put(name, new Node(this, name));
      }

      return myKids.get(name);
    }

    @Override
    protected void doUpdate() {
      setPlainText(myName);
    }

    @Override
    protected SimpleNode[] buildChildren() {
      return myKids.isEmpty() ? NO_CHILDREN : myKids.values().toArray(new Node[0]);
    }

    @Override
    public String toString() {
      return myName;
    }

    @Override
    protected void updateFileStatus() {
    }
  }

  private Object findNode(final String name) {
    final Ref<Object> node = new Ref<>();
    ((SimpleTree)myTree).accept(myBuilder, new SimpleNodeVisitor() {
      @Override
      public boolean accept(final SimpleNode simpleNode) {
        if (name.equals(simpleNode.toString())) {
          node.set(myBuilder.getOriginalNode(simpleNode));
          return true;
        } else {
          return false;
        }
      }
    });

    return node.get();
  }


  private static class MyFilter extends ElementFilter.Active.Impl {

    String myPattern = "";

    @Override
    public boolean shouldBeShowing(final Object value) {
      return value.toString().startsWith(myPattern);
    }

    @NotNull
    public Promise<?> update(final String pattern, Object selection) {
      myPattern = pattern;
      return fireUpdate(selection, true, false);
    }
  }

  @Override
  AbstractTreeBuilder getBuilder() {
    return myBuilder;
  }
}


