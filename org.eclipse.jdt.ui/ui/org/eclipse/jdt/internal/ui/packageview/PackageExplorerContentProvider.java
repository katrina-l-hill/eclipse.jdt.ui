/*******************************************************************************
 * Copyright (c) 2000, 2013 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.ui.packageview;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.swt.widgets.Widget;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;

import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.jface.viewers.IBasicPropertyConstants;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;

import org.eclipse.ui.IWorkingSet;
import org.eclipse.ui.progress.UIJob;

import org.eclipse.jdt.core.ElementChangedEvent;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IElementChangedListener;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaElementDelta;
import org.eclipse.jdt.core.IJavaModel;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IModuleDescription;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.ITypeRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;

import org.eclipse.jdt.internal.corext.util.JavaModelUtil;

import org.eclipse.jdt.ui.PreferenceConstants;
import org.eclipse.jdt.ui.StandardJavaElementContentProvider;

import org.eclipse.jdt.internal.ui.JavaPlugin;
import org.eclipse.jdt.internal.ui.workingsets.WorkingSetModel;

/**
 * Content provider for the PackageExplorer.
 *
 * <p>
 * Since 2.1 this content provider can provide the children for flat or hierarchical
 * layout.
 * </p>
 *
 * @see org.eclipse.jdt.ui.StandardJavaElementContentProvider
 */
public class PackageExplorerContentProvider extends StandardJavaElementContentProvider implements IElementChangedListener, IPropertyChangeListener {

	protected static final int ORIGINAL= 0;
	protected static final int PARENT= 1 << 0;
	protected static final int GRANT_PARENT= 1 << 1;
	protected static final int PROJECT= 1 << 2;

	private TreeViewer fViewer;
	private Object fInput;
	private boolean fIsFlatLayout;
	private boolean fShowLibrariesNode;
	private boolean fFoldPackages;

	private Collection<Runnable> fPendingUpdates;

	private UIJob fUpdateJob;

	/**
	 * We use a cache to know whether a package has a single child for the hierarchical representation.
	 * This avoids looping over all packages for each call to
	 * {@link #getHierarchicalPackageParent(IPackageFragment)}. The cache is cleared on any Java model
	 * change, as we aim to improve operations which go over all packages on by one.
	 */
	private final PackageCache.PerRootCache packageCache;

	/**
	 * Creates a new content provider for Java elements.
	 * @param provideMembers if set, members of compilation units and class files are shown
	 */
	public PackageExplorerContentProvider(boolean provideMembers) {
		super(provideMembers);
		fShowLibrariesNode= false;
		fIsFlatLayout= false;
		fFoldPackages= arePackagesFoldedInHierarchicalLayout();
		fPendingUpdates= null;
		JavaPlugin.getDefault().getPreferenceStore().addPropertyChangeListener(this);

		fUpdateJob= null;
		packageCache= new PackageCache.PerRootCache();
	}

	private boolean arePackagesFoldedInHierarchicalLayout(){
		return PreferenceConstants.getPreferenceStore().getBoolean(PreferenceConstants.APPEARANCE_FOLD_PACKAGES_IN_PACKAGE_EXPLORER);
	}

	protected Object getViewerInput() {
		return fInput;
	}

	@Override
	public void elementChanged(final ElementChangedEvent event) {
		final ArrayList<Runnable> runnables= new ArrayList<>();
		try {
			clearPackageCache();

			// 58952 delete project does not update Package Explorer [package explorer]
			// if the input to the viewer is deleted then refresh to avoid the display of stale elements
			if (inputDeleted(runnables))
				return;

			processDelta(event.getDelta(), runnables);
		} catch (JavaModelException e) {
			JavaPlugin.log(e);
		} finally {
			executeRunnables(runnables);
		}
	}

	protected final void executeRunnables(final Collection<Runnable> runnables) {

		// now post all collected runnables
		Control ctrl= fViewer.getControl();
		if (ctrl != null && !ctrl.isDisposed()) {
			final boolean hasPendingUpdates;
			synchronized (this) {
				hasPendingUpdates= fPendingUpdates != null && !fPendingUpdates.isEmpty();
			}
			//Are we in the UIThread? If so spin it until we are done
			if (!hasPendingUpdates && ctrl.getDisplay().getThread() == Thread.currentThread() && !fViewer.isBusy()) {
				runUpdates(runnables);
			} else {
				synchronized (this) {
					if (fPendingUpdates == null) {
						fPendingUpdates= runnables;
					} else {
						fPendingUpdates.addAll(runnables);
					}
					postAsyncUpdate(ctrl.getDisplay());
				}
			}
		}
	}
	private void postAsyncUpdate(final Display display) {
		if (fUpdateJob == null) {
			fUpdateJob= new UIJob(display, PackagesMessages.PackageExplorerContentProvider_update_job_description) {
				@Override
				public IStatus runInUIThread(IProgressMonitor monitor) {
					TreeViewer viewer= fViewer;
					if (viewer != null && viewer.isBusy()) {
						schedule(100); // reschedule when viewer is busy: bug 184991
					} else {
						runPendingUpdates();
					}
					return Status.OK_STATUS;
				}
			};
			fUpdateJob.setSystem(true);
		}
		fUpdateJob.schedule();
	}

	/**
	 * Run all of the runnables that are the widget updates. Must be called in the display thread.
	 */
	public void runPendingUpdates() {
		Collection<Runnable> pendingUpdates;
		synchronized (this) {
			pendingUpdates= fPendingUpdates;
			fPendingUpdates= null;
		}
		if (pendingUpdates != null && fViewer != null) {
			Control control = fViewer.getControl();
			if (control != null && !control.isDisposed()) {
				runUpdates(pendingUpdates);
			}
		}
	}

	private void runUpdates(Collection<Runnable> runnables) {
		Iterator<Runnable> runnableIterator = runnables.iterator();
		while (runnableIterator.hasNext()){
			runnableIterator.next().run();
		}
	}


	private boolean inputDeleted(Collection<Runnable> runnables) {
		if (fInput == null)
			return false;
		if (fInput instanceof IJavaElement && ((IJavaElement) fInput).exists())
			return false;
		if (fInput instanceof IResource && ((IResource) fInput).exists())
			return false;
		if (fInput instanceof WorkingSetModel)
			return false;
		if (fInput instanceof IWorkingSet) // see https://bugs.eclipse.org/bugs/show_bug.cgi?id=156239
			return false;
		postRefresh(fInput, ORIGINAL, fInput, runnables);
		return true;
	}

	@Override
	public void dispose() {
		clearPackageCache();
		JavaCore.removeElementChangedListener(this);
		JavaPlugin.getDefault().getPreferenceStore().removePropertyChangeListener(this);
		super.dispose();
	}

	private void clearPackageCache() {
		packageCache.clear();
	}

	@Override
	protected Object[] getPackageFragmentRootContent(IPackageFragmentRoot root) throws JavaModelException {
		if (fIsFlatLayout) {
			return super.getPackageFragmentRootContent(root);
		}

		// hierarchical package mode
		ArrayList<Object> result= new ArrayList<>();
		getHierarchicalPackageRootChildren(root, result);
		if (!isProjectPackageFragmentRoot(root)) {
			Object[] nonJavaResources= root.getNonJavaResources();
			result.addAll(Arrays.asList(nonJavaResources));
		}
		return result.toArray();
	}

	@Override
	protected Object[] getPackageContent(IPackageFragment fragment) throws JavaModelException {
		if (fIsFlatLayout) {
			return super.getPackageContent(fragment);
		}

		// hierarchical package mode
		ArrayList<Object> result= new ArrayList<>();

		getHierarchicalPackageChildren(fragment, result);
		Object[] nonPackages= super.getPackageContent(fragment);
		if (result.isEmpty())
			return nonPackages;
		result.addAll(Arrays.asList(nonPackages));
		return result.toArray();
	}

	@Override
	protected Object[] getFolderContent(IFolder folder) throws CoreException {
		if (fIsFlatLayout) {
			return super.getFolderContent(folder);
		}

		// hierarchical package mode
		ArrayList<Object> result= new ArrayList<>();

		getHierarchicalPackagesInFolder(folder, result);
		Object[] others= super.getFolderContent(folder);
		if (result.isEmpty())
			return others;
		result.addAll(Arrays.asList(others));
		return result.toArray();
	}


	@Override
	public Object[] getChildren(Object parentElement) {
		try {
			if (parentElement instanceof IJavaModel)
				return concatenate(getJavaProjects((IJavaModel)parentElement), getNonJavaProjects((IJavaModel)parentElement));

			if (parentElement instanceof PackageFragmentRootContainer)
				return getContainerPackageFragmentRoots((PackageFragmentRootContainer)parentElement);

			if (parentElement instanceof IProject) {
				IProject project= (IProject) parentElement;
				if (project.isAccessible())
					return project.members();
				return NO_CHILDREN;
			}

			return super.getChildren(parentElement);
		} catch (CoreException e) {
			return NO_CHILDREN;
		}
	}

	@Override
	protected Object[] getPackageFragmentRoots(IJavaProject project) throws JavaModelException {
		if (!project.getProject().isOpen())
			return NO_CHILDREN;

		List<Object> result= new ArrayList<>();

		for (IPackageFragmentRoot root : project.getPackageFragmentRoots()) {
			IClasspathEntry classpathEntry= root.getRawClasspathEntry();
			int entryKind= classpathEntry.getEntryKind();
			if (entryKind == IClasspathEntry.CPE_CONTAINER) {
				// all ClassPathContainers are added later
			} else if (fShowLibrariesNode && (entryKind == IClasspathEntry.CPE_LIBRARY || entryKind == IClasspathEntry.CPE_VARIABLE)) {
				IResource resource= root.getResource();
				if (resource != null && project.getResource().equals(resource.getParent())) {
					// show resource as child of project, see https://bugs.eclipse.org/bugs/show_bug.cgi?id=141906
					result.add(resource);
				} else {
					// skip: will add the referenced library node later
				}
			} else {
				if (isProjectPackageFragmentRoot(root)) {
					// filter out package fragments that correspond to projects and
					// replace them with the package fragments directly
					Object[] fragments= getPackageFragmentRootContent(root);
					result.addAll(Arrays.asList(fragments));
				} else {
					result.add(root);
				}
			}
		}

		if (fShowLibrariesNode) {
			result.add(new LibraryContainer(project));
		}

		// separate loop to make sure all containers are on the classpath (even empty ones)
		for (IClasspathEntry classpathEntry : project.getRawClasspath()) {
			if (classpathEntry.getEntryKind() == IClasspathEntry.CPE_CONTAINER) {
				result.add(new ClassPathContainer(project, classpathEntry));
			}
		}
		Object[] resources= project.getNonJavaResources();
		result.addAll(Arrays.asList(resources));
		return result.toArray();
	}

	private Object[] getContainerPackageFragmentRoots(PackageFragmentRootContainer container) {
		return container.getChildren();
	}

	private Object[] getNonJavaProjects(IJavaModel model) throws JavaModelException {
		return model.getNonJavaResources();
	}

	@Override
	protected Object internalGetParent(Object element) {
		if (!fIsFlatLayout && element instanceof IPackageFragment) {
			return getHierarchicalPackageParent((IPackageFragment) element);
		} else if (element instanceof IPackageFragmentRoot) {
			// since we insert logical package containers we have to fix
			// up the parent for package fragment roots so that they refer
			// to the container and containers refer to the project
			IPackageFragmentRoot root= (IPackageFragmentRoot)element;

			try {
				IClasspathEntry entry= root.getRawClasspathEntry();
				int entryKind= entry.getEntryKind();
				if (entryKind == IClasspathEntry.CPE_CONTAINER) {
					return new ClassPathContainer(root.getJavaProject(), entry);
				} else if (fShowLibrariesNode && (entryKind == IClasspathEntry.CPE_LIBRARY || entryKind == IClasspathEntry.CPE_VARIABLE)) {
					return new LibraryContainer(root.getJavaProject());
				}
			} catch (JavaModelException e) {
				// fall through
			}
		} else if (element instanceof PackageFragmentRootContainer) {
			return ((PackageFragmentRootContainer)element).getJavaProject();
		}
		return super.internalGetParent(element);
	}

	@Override
	public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
		super.inputChanged(viewer, oldInput, newInput);
		fViewer= (TreeViewer)viewer;
		if (oldInput == null && newInput != null) {
			JavaCore.addElementChangedListener(this);
		} else if (oldInput != null && newInput == null) {
			JavaCore.removeElementChangedListener(this);
		}
		fInput= newInput;
	}

	// hierarchical packages
	/**
	 * Returns the hierarchical packages inside a given root.
	 *
	 * @param parent the parent package fragment root
	 * @param result Collection where the resulting elements are added
	 * @throws JavaModelException if fetching the children fails
	 */
	private void getHierarchicalPackageRootChildren(IPackageFragmentRoot parent, Collection<Object> result) throws JavaModelException {
		boolean is9OrHigher= JavaModelUtil.is9OrHigher(parent.getJavaProject());
		for (IJavaElement child : parent.getChildren()) {
			IPackageFragment curr= (IPackageFragment) child;
			String name= curr.getElementName();
			if (!name.isEmpty() && name.indexOf('.') == -1) {
				if (fFoldPackages) {
					curr= getFolded(curr);
				}
				result.add(curr);
			} else if (curr.isDefaultPackage()) {
				if (isRelevantPackage(curr, is9OrHigher))
					result.add(curr);
				IJavaElement emptyModuleInfo= emptyModuleInfo(curr, is9OrHigher);
				if (emptyModuleInfo != null)
					result.add(emptyModuleInfo);
			}
		}

		if (is9OrHigher) {
			IModuleDescription module= parent.getModuleDescription();
			if (module != null) {
				result.add(module.getParent());
			}
		}
	}

	/**
	 * Returns the hierarchical packages inside a given fragment.
	 *
	 * @param fragment the package to get the children for or 'null' to get the children of the root
	 * @param result Collection where the resulting elements are added
	 * @throws JavaModelException if fetching the children fails
	 */
	private void getHierarchicalPackageChildren(IPackageFragment fragment, Collection<Object> result) throws JavaModelException {
		List<IPackageFragment> children = packageCache.getDirectChildren(fragment);
		for (IPackageFragment child : children) {
			if (fFoldPackages) {
				child= getFolded(child);
			}
			result.add(child);
		}
	}

	boolean isRelevantPackage(IPackageFragment fragment, boolean is9OrHigher) throws JavaModelException {
		if (is9OrHigher && !JavaModelUtil.containsOrdinaryCompilationUnit(fragment)) {
			// at 9, a default package containing only module-info should be hidden:
			return false;
		}
		return true;
	}

	IJavaElement emptyModuleInfo(IPackageFragment fragment, boolean is9OrHigher) throws JavaModelException {
		if (is9OrHigher) {
			// at 9, empty module-info.java should be added
			for (IJavaElement child : fragment.getChildren()) {
				if (child instanceof ITypeRoot && ((ITypeRoot) child).getChildren().length == 0)
					return child;
			}
		}
		return null;
	}

	/**
	 * Returns the hierarchical packages inside a given folder.
	 * @param folder The parent folder
	 * @param result Collection where the resulting elements are added
	 * @throws CoreException thrown when elements could not be accessed
	 */
	private void getHierarchicalPackagesInFolder(IFolder folder, Collection<Object> result) throws CoreException {
		for (IResource resource : folder.members()) {
			if (resource instanceof IFolder) {
				IFolder curr= (IFolder) resource;
				IJavaElement element= JavaCore.create(curr);
				if (element instanceof IPackageFragment) {
					if (fFoldPackages) {
						IPackageFragment fragment= (IPackageFragment) element;
						element= getFolded(fragment);
					}
					result.add(element);
				}
			}
		}
	}

	public Object getHierarchicalPackageParent(IPackageFragment child) {
		String name= child.getElementName();
		IPackageFragmentRoot parent= (IPackageFragmentRoot) child.getParent();
		int index= name.lastIndexOf('.');
		if (index != -1) {
			String realParentName= name.substring(0, index);
			IPackageFragment element= parent.getPackageFragment(realParentName);
			if (element.exists()) {
				try {
					if (fFoldPackages && isEmpty(element) && packageCache.hasSingleChild(element)) {
						return getHierarchicalPackageParent(element);
					}
				} catch (JavaModelException e) {
					// ignore
				}
				return element;
			} else { // bug 65240
				IResource resource= element.getResource();
				if (resource != null) {
					return resource;
				}
			}
		}
		if (parent.getResource() instanceof IProject) {
			return parent.getJavaProject();
		}
		return parent;
	}

	private IPackageFragment getFolded(IPackageFragment pack) throws JavaModelException {
		while (isEmpty(pack)) {
			IPackageFragment collapsed= packageCache.getSingleChild(pack);
			if (collapsed == null) {
				return pack;
			}
			pack= collapsed;
		}
		return pack;
	}

	private static boolean isEmpty(IPackageFragment fragment) throws JavaModelException {
		return !fragment.containsJavaResources() && fragment.getNonJavaResources().length == 0;
	}

	// ------ delta processing ------

	/**
	 * Processes a delta recursively. When more than two children are affected the
	 * tree is fully refreshed starting at this node.
	 *
	 * @param delta the delta to process
	 * @param runnables the resulting view changes as runnables (type {@link Runnable})
	 * @return true is returned if the conclusion is to refresh a parent of an element. In that case no siblings need
	 * to be processed
	 * @throws JavaModelException thrown when the access to an element failed
	 */
	private boolean processDelta(IJavaElementDelta delta, Collection<Runnable> runnables) throws JavaModelException {

		int kind= delta.getKind();
		int flags= delta.getFlags();
		IJavaElement element= delta.getElement();
		int elementType= element.getElementType();


		if (elementType != IJavaElement.JAVA_MODEL && elementType != IJavaElement.JAVA_PROJECT) {
			IJavaProject proj= element.getJavaProject();
			if (proj == null || !proj.getProject().isOpen()) // TODO: Not needed if parent already did the 'open' check!
				return false;
		}

		if (elementType == IJavaElement.PACKAGE_FRAGMENT) {
			if ((flags & (IJavaElementDelta.F_CONTENT | IJavaElementDelta.F_CHILDREN)) == IJavaElementDelta.F_CONTENT) {
				// TODO: This should never be true for folders (F_CONTENT is only for files)
				if (!fIsFlatLayout) {
					Object parent = getHierarchicalPackageParent((IPackageFragment) element);
					if (!(parent instanceof IPackageFragmentRoot)) {
						postRefresh(internalGetParent(parent), GRANT_PARENT, element, runnables);
						return true;
					}
				}
				// content change, without children info (for example resource added/removed to class folder package)
				postRefresh(internalGetParent(element), PARENT, element, runnables);
				return true;
			}

			if (!fIsFlatLayout) {
				if (kind == IJavaElementDelta.REMOVED) {
					final Object parent = getHierarchicalPackageParent((IPackageFragment) element);
					if (parent instanceof IPackageFragmentRoot) {
						postRemove(element,  runnables);
						return false;
					} else {
						postRefresh(internalGetParent(parent), GRANT_PARENT, element, runnables);
						return true;
					}
				} else if (kind == IJavaElementDelta.ADDED) {
					final Object parent = getHierarchicalPackageParent((IPackageFragment) element);
					if (parent instanceof IPackageFragmentRoot) {
						if (fFoldPackages) {
							postRefresh(parent, PARENT, element, runnables);
							return true;
						} else {
							postAdd(parent, element, runnables);
							return false;
						}
					} else {
						postRefresh(internalGetParent(parent), GRANT_PARENT, element, runnables);
						return true;
					}
				}
				handleAffectedChildren(delta, element, runnables);
				return false;
			}
		}

		if (elementType == IJavaElement.COMPILATION_UNIT) {
			ICompilationUnit cu= (ICompilationUnit) element;
			if (!JavaModelUtil.isPrimary(cu)) {
				return false;
			}

			if (!getProvideMembers() && cu.isWorkingCopy() && kind == IJavaElementDelta.CHANGED) {
				return false;
			}

			if (kind == IJavaElementDelta.CHANGED && !isStructuralCUChange(flags)) {
				return false; // test moved ahead
			}

			if (!isOnClassPath(cu)) { // TODO: isOnClassPath expensive! Should be put after all cheap tests
				return false;
			}

		}

		if (elementType == IJavaElement.JAVA_PROJECT) {
			// handle open and closing of a project
			if ((flags & (IJavaElementDelta.F_CLOSED | IJavaElementDelta.F_OPENED)) != 0) {
				postRefresh(element, ORIGINAL, element, runnables);
				return false;
			}
			// if the class path has changed we refresh the entire project
			if ((flags & (IJavaElementDelta.F_CLASSPATH_CHANGED | IJavaElementDelta.F_RESOLVED_CLASSPATH_CHANGED)) != 0) {
				postRefresh(element, ORIGINAL, element, runnables);
				return false;
			}
			// if added it could be that the corresponding IProject is already shown. Remove it first.
			// bug 184296
			if (kind == IJavaElementDelta.ADDED) {
				postRemove(element.getResource(), runnables);
				postAdd(element.getParent(), element, runnables);
				return false;
			}
		}

		if (kind == IJavaElementDelta.REMOVED) {
			Object parent= internalGetParent(element);
			if (element instanceof IPackageFragment) {
				// refresh package fragment root to allow filtering empty (parent) packages: bug 72923
				if (fViewer.testFindItem(parent) != null)
					postRefresh(parent, PARENT, element, runnables);
				return true;

			} else if (element instanceof IPackageFragmentRoot) {
				// libs and class folders can show up twice (in library container and as resource at original location)
				IResource resource= element.getResource();
				if (resource != null && !resource.exists())
					postRemove(resource, runnables);
			}

			postRemove(element, runnables);
			if (parent instanceof IPackageFragment)
				postUpdateIcon((IPackageFragment)parent, runnables);
			// we are filtering out empty subpackages, so we
			// a package becomes empty we remove it from the viewer.
			if (isPackageFragmentEmpty(element.getParent())) {
				if (fViewer.testFindItem(parent) != null)
					postRefresh(internalGetParent(parent), GRANT_PARENT, element, runnables);
				return true;
			}
			return false;
		}

		if (kind == IJavaElementDelta.ADDED) {
			Object parent= internalGetParent(element);
			// we are filtering out empty subpackages, so we
			// have to handle additions to them specially.
			if (parent instanceof IPackageFragment) {
				Object grandparent= internalGetParent(parent);
				// 1GE8SI6: ITPJUI:WIN98 - Rename is not shown in Packages View
				// avoid posting a refresh to an invisible parent
				if (parent.equals(fInput) || fViewer.testFindItem(parent) != null) {
					postRefresh(parent, PARENT, element, runnables);
				} else {// Refresh from grandparent if parent isn't visible yet
					postRefresh(grandparent, GRANT_PARENT, element, runnables);
				}
				return true;
			} else {
				if (element instanceof IPackageFragmentRoot
						&& ((IPackageFragmentRoot)element).getKind() != IPackageFragmentRoot.K_SOURCE) {
					// libs and class folders can show up twice (in library container or under project, and as resource at original location)
					IResource resource= element.getResource();
					if (resource != null) {
						Object resourceParent= super.internalGetParent(resource);
						if (resourceParent != null) {
							IJavaProject proj= element.getJavaProject();
							if (fShowLibrariesNode || !resourceParent.equals(proj)) {
								postAdd(resourceParent, resource, runnables);
							}
						}
					}
				}
				postAdd(parent, element, runnables);
			}
		}

		if (elementType == IJavaElement.COMPILATION_UNIT || elementType == IJavaElement.CLASS_FILE) {
			if (kind == IJavaElementDelta.CHANGED) {
				// isStructuralCUChange already performed above
				postRefresh(element, ORIGINAL, element, runnables);
			}
			return false;
		}

		if (elementType == IJavaElement.PACKAGE_FRAGMENT_ROOT) {
			// the contents of an external JAR has changed
			if ((flags & IJavaElementDelta.F_ARCHIVE_CONTENT_CHANGED) != 0) {
				postRefresh(element, ORIGINAL, element, runnables);
				return false;
			}
			// http://bugs.eclipse.org/bugs/show_bug.cgi?id=357450
			int result= flags & (IJavaElementDelta.F_CONTENT | IJavaElementDelta.F_CHILDREN);
			Object parent= internalGetParent(element);
			boolean isParentLibrayContainer= parent instanceof LibraryContainer;
			if (result == IJavaElementDelta.F_CONTENT ||
					(result == IJavaElementDelta.F_CHILDREN && isParentLibrayContainer)) {
				postRefresh(parent, PARENT, element, runnables);
				if (isParentLibrayContainer) {
					IResource resource= element.getResource();
					if (resource != null && ((LibraryContainer) parent).getJavaProject().getResource().equals(resource.getProject()))
						postRefresh(resource, ORIGINAL, element, runnables);
				}
				return true;
			}

			// the source attachment of a JAR has changed
			if ((flags & (IJavaElementDelta.F_SOURCEATTACHED | IJavaElementDelta.F_SOURCEDETACHED)) != 0)
				postUpdateIcon(element, runnables);

			if (isClassPathChange(delta)) {
				 // throw the towel and do a full refresh of the affected java project.
				postRefresh(element.getJavaProject(), PROJECT, element, runnables);
				return true;
			}
		}

		handleAffectedChildren(delta, element, runnables);
		return false;
	}

	private static boolean isStructuralCUChange(int flags) {
		// No refresh on working copy creation (F_PRIMARY_WORKING_COPY)
		return (flags & IJavaElementDelta.F_CHILDREN) != 0 || (flags & (IJavaElementDelta.F_CONTENT | IJavaElementDelta.F_FINE_GRAINED)) == IJavaElementDelta.F_CONTENT;
	}

	/* package */ void handleAffectedChildren(IJavaElementDelta delta, IJavaElement element, Collection<Runnable> runnables) throws JavaModelException {
		int count= 0;

		IResourceDelta[] resourceDeltas= delta.getResourceDeltas();
		if (resourceDeltas != null) {
			for (IResourceDelta resourceDelta : resourceDeltas) {
				int kind= resourceDelta.getKind();
				if (kind == IResourceDelta.ADDED || kind == IResourceDelta.REMOVED) {
					count++;
				}
			}
		}
		IJavaElementDelta[] affectedChildren= delta.getAffectedChildren();
		for (IJavaElementDelta affectedChildren1 : affectedChildren) {
			int kind= affectedChildren1.getKind();
			if (kind == IJavaElementDelta.ADDED || kind == IJavaElementDelta.REMOVED) {
				count++;
			}
		}

		if (count > 1) {
			// more than one child changed, refresh from here downwards
			if (element instanceof IPackageFragment) {
				// a package fragment might become non empty refresh from the parent
				IJavaElement parent= (IJavaElement) internalGetParent(element);
				// 1GE8SI6: ITPJUI:WIN98 - Rename is not shown in Packages View
				// avoid posting a refresh to an invisible parent
				if (element.equals(fInput)) {
					postRefresh(element, ORIGINAL, element, runnables);
				} else {
					postRefresh(parent, PARENT, element, runnables);
				}
			} else if (element instanceof IPackageFragmentRoot) {
				Object toRefresh= internalGetParent(element);
				postRefresh(toRefresh, ORIGINAL, toRefresh, runnables);
			} else {
				postRefresh(element, ORIGINAL, element, runnables);
			}
			return;
		}
		if (resourceDeltas != null) {
			for (IResourceDelta resourceDelta : resourceDeltas) {
				if (processResourceDelta(resourceDelta, element, runnables)) {
					return; // early return, element got refreshed
				}
			}
		}
		for (IJavaElementDelta child : affectedChildren) {
			if (processDelta(child, runnables)) {
				return; // early return, element got refreshed
			}
		}
	}

	protected void processAffectedChildren(IJavaElementDelta[] affectedChildren, Collection<Runnable> runnables) throws JavaModelException {
		for (IJavaElementDelta child : affectedChildren) {
			processDelta(child, runnables);
		}
	}

	private boolean isOnClassPath(ICompilationUnit element) {
		IJavaProject project= element.getJavaProject();
		if (project == null || !project.exists())
			return false;
		return project.isOnClasspath(element);
	}

	/**
	 * Updates the package icon
	 * @param element the element to update
	 * @param runnables the resulting view changes as runnables (type {@link Runnable})
	 */
	 private void postUpdateIcon(final IJavaElement element, Collection<Runnable> runnables) {
		 runnables.add(() -> fViewer.update(element, new String[]{IBasicPropertyConstants.P_IMAGE}));
	 }

	/**
	 * Process a resource delta.
	 *
	 * @param delta the delta to process
	 * @param parent the parent
	 * @param runnables the resulting view changes as runnables (type {@link Runnable})
	 * @return true if the parent got refreshed
	 */
	private boolean processResourceDelta(IResourceDelta delta, Object parent, Collection<Runnable> runnables) {
		int status= delta.getKind();
		int flags= delta.getFlags();

		IResource resource= delta.getResource();
		// filter out changes affecting the output folder
		if (resource == null)
			return false;

		// this could be optimized by handling all the added children in the parent
		if ((status & IResourceDelta.REMOVED) != 0) {
			if (parent instanceof IPackageFragment) {
				// refresh one level above to deal with empty package filtering properly
				postRefresh(internalGetParent(parent), PARENT, parent, runnables);
				return true;
			} else {
				postRemove(resource, runnables);
				return false;
			}
		}
		if ((status & IResourceDelta.ADDED) != 0) {
			if (parent instanceof IPackageFragment) {
				// refresh one level above to deal with empty package filtering properly
				postRefresh(internalGetParent(parent), PARENT, parent, runnables);
				return true;
			} else {
				postAdd(parent, resource, runnables);
				return false;
			}
		}
		if ((status & IResourceDelta.CHANGED) != 0) {
			if ((flags & IResourceDelta.TYPE) != 0) {
				postRefresh(parent, PARENT, resource, runnables);
				return true;
			}
		}
		// open/close state change of a project
		if ((flags & IResourceDelta.OPEN) != 0) {
			postProjectStateChanged(internalGetParent(parent), runnables);
			return true;
		}
		IResourceDelta[] resourceDeltas= delta.getAffectedChildren();

		int count= 0;
		for (IResourceDelta resourceDelta : resourceDeltas) {
			int kind= resourceDelta.getKind();
			if (kind == IResourceDelta.ADDED || kind == IResourceDelta.REMOVED) {
				count++;
				if (count > 1) {
					postRefresh(parent, PARENT, resource, runnables);
					return true;
				}
			}
		}
		for (IResourceDelta resourceDelta : resourceDeltas) {
			if (processResourceDelta(resourceDelta, resource, runnables)) {
				return false; // early return, element got refreshed
			}
		}
		return false;
	}

	public void setIsFlatLayout(boolean state) {
		fIsFlatLayout= state;
	}

	public void setShowLibrariesNode(boolean state) {
		fShowLibrariesNode= state;
	}

	private void postRefresh(Object root, int relation, Object affectedElement, Collection<Runnable> runnables) {
		// JFace doesn't refresh when object isn't part of the viewer
		// Therefore move the refresh start down to the viewer's input
		if (isParent(root, fInput) || root instanceof IJavaModel)
			root= fInput;
		List<Object> toRefresh= new ArrayList<>(1);
		toRefresh.add(root);
		augmentElementToRefresh(toRefresh, relation, affectedElement);
		postRefresh(toRefresh, true, runnables);
	}

	/**
	 * Can be implemented by subclasses to add additional elements to refresh
	 *
	 * @param toRefresh the elements to refresh
	 * @param relation the relation to the affected element ({@link #GRANT_PARENT}, {@link #PARENT}, {@link #ORIGINAL}, {@link #PROJECT})
	 * @param affectedElement the affected element
	 */
	protected void augmentElementToRefresh(List<Object> toRefresh, int relation, Object affectedElement) {
	}

	private boolean isParent(Object root, Object child) {
		Object parent= getParent(child);
		if (parent == null)
			return false;
		if (parent.equals(root))
			return true;
		return isParent(root, parent);
	}

	protected void postRefresh(final List<Object> toRefresh, final boolean updateLabels, Collection<Runnable> runnables) {
		runnables.add(() -> {
			for (Object element : toRefresh.toArray()) {
				if (element == null || fViewer.testFindItems(element).length > 0) {
					fViewer.refresh(element, updateLabels);
				}
			}
		});
	}

	protected void postAdd(final Object parent, final Object element, Collection<Runnable> runnables) {
		runnables.add(() -> {
			for (Widget item : fViewer.testFindItems(element)) {
				if (item instanceof TreeItem && !item.isDisposed()) {
					TreeItem parentItem= ((TreeItem) item).getParentItem();
					if (parentItem != null && !parentItem.isDisposed() && parent.equals(parentItem.getData())) {
						return; // no add, element already added (most likely by a refresh)
					}
				}
			}
			fViewer.add(parent, element);
		});
	}

	protected void postRemove(final Object element, Collection<Runnable> runnables) {
		runnables.add(() -> {
			if (fViewer.testFindItems(element).length > 0) {
				fViewer.remove(element);
			}
		});
	}

	protected void postProjectStateChanged(final Object root, Collection<Runnable> runnables) {
		runnables.add(() -> {
			fViewer.refresh(root, true);
			// trigger a synthetic selection change so that action refresh their
			// enable state.
			fViewer.setSelection(fViewer.getSelection());
		});
	}


	/*
	 * @see org.eclipse.jface.util.IPropertyChangeListener#propertyChange(org.eclipse.jface.util.PropertyChangeEvent)
	 */
	@Override
	public void propertyChange(PropertyChangeEvent event) {
		if (arePackagesFoldedInHierarchicalLayout() != fFoldPackages){
			fFoldPackages= arePackagesFoldedInHierarchicalLayout();
			if (fViewer != null && !fViewer.getControl().isDisposed()) {
				fViewer.getControl().setRedraw(false);
				Object[] expandedObjects= fViewer.getExpandedElements();
				fViewer.refresh();
				fViewer.setExpandedElements(expandedObjects);
				fViewer.getControl().setRedraw(true);
			}
		}
	}
}
