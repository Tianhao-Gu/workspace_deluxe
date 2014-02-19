package us.kbase.workspace.kbase;

import static us.kbase.common.utils.ServiceUtils.checkAddlArgs;
import static us.kbase.workspace.kbase.KBaseIdentifierFactory.processWorkspaceIdentifier;
import static us.kbase.workspace.kbase.KBasePermissions.translatePermission;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import us.kbase.auth.AuthException;
import us.kbase.auth.AuthToken;
import us.kbase.common.service.Tuple11;
import us.kbase.common.service.Tuple9;
import us.kbase.typedobj.core.TypeDefId;
import us.kbase.typedobj.exceptions.BadJsonSchemaDocumentException;
import us.kbase.typedobj.exceptions.InstanceValidationException;
import us.kbase.typedobj.exceptions.NoSuchPrivilegeException;
import us.kbase.typedobj.exceptions.TypeStorageException;
import us.kbase.typedobj.exceptions.TypedObjectValidationException;
import us.kbase.workspace.CreateWorkspaceParams;
import us.kbase.workspace.GrantModuleOwnershipParams;
import us.kbase.workspace.ObjectSaveData;
import us.kbase.workspace.RemoveModuleOwnershipParams;
import us.kbase.workspace.SaveObjectsParams;
import us.kbase.workspace.SetGlobalPermissionsParams;
import us.kbase.workspace.SetPermissionsParams;
import us.kbase.workspace.WorkspaceIdentity;
import us.kbase.workspace.database.ObjectIDNoWSNoVer;
import us.kbase.workspace.database.ObjectInformation;
import us.kbase.workspace.database.Permission;
import us.kbase.workspace.database.Provenance;
import us.kbase.workspace.database.User;
import us.kbase.workspace.database.WorkspaceIdentifier;
import us.kbase.workspace.database.WorkspaceInformation;
import us.kbase.workspace.database.WorkspaceUser;
import us.kbase.workspace.database.exceptions.CorruptWorkspaceDBException;
import us.kbase.workspace.database.exceptions.NoSuchObjectException;
import us.kbase.workspace.database.exceptions.NoSuchWorkspaceException;
import us.kbase.workspace.database.exceptions.PreExistingWorkspaceException;
import us.kbase.workspace.database.exceptions.WorkspaceCommunicationException;
import us.kbase.workspace.exceptions.WorkspaceAuthorizationException;
import us.kbase.workspace.lib.WorkspaceSaveObject;
import us.kbase.workspace.lib.Workspace;

public class WorkspaceServerMethods {
	
	final Workspace ws;
	final ArgUtils au = new ArgUtils();
	
	public WorkspaceServerMethods(final Workspace ws) {
		this.ws = ws;
	}
	
	public Tuple9<Long, String, String, String, Long, String, String, String, Map<String, String>>
			createWorkspace(
			final CreateWorkspaceParams params, final WorkspaceUser user)
			throws PreExistingWorkspaceException,
			WorkspaceCommunicationException, CorruptWorkspaceDBException {
		checkAddlArgs(params.getAdditionalProperties(), params.getClass());
		Permission p = au.getGlobalWSPerm(params.getGlobalread());
		final WorkspaceInformation meta = ws.createWorkspace(user,
				params.getWorkspace(), p.equals(Permission.READ),
				params.getDescription(), params.getMeta());
		return au.wsInfoToTuple(meta);
	}
	
	public void setPermissions(final SetPermissionsParams params,
			final WorkspaceUser user, final AuthToken token)
			throws IOException, AuthException, CorruptWorkspaceDBException,
			NoSuchWorkspaceException, WorkspaceAuthorizationException,
			WorkspaceCommunicationException {
		checkAddlArgs(params.getAdditionalProperties(), params.getClass());
		final WorkspaceIdentifier wsi = processWorkspaceIdentifier(
				params.getWorkspace(), params.getId());
		final Permission p = translatePermission(params.getNewPermission());
		if (params.getUsers().size() == 0) {
			throw new IllegalArgumentException("Must provide at least one user");
		}
		final List<WorkspaceUser> users = ArgUtils.validateUsers(
				params.getUsers(), token);
		ws.setPermissions(user, wsi, users, p);
	}

	public void setGlobalPermission(final SetGlobalPermissionsParams params,
			WorkspaceUser user)
			throws CorruptWorkspaceDBException, NoSuchWorkspaceException,
			WorkspaceAuthorizationException, WorkspaceCommunicationException {
		checkAddlArgs(params.getAdditionalProperties(), params.getClass());
		final WorkspaceIdentifier wsi = processWorkspaceIdentifier(
				params.getWorkspace(), params.getId());
		final Permission p = translatePermission(params.getNewPermission());
		ws.setGlobalPermission(user, wsi, p);
	}
	
	public Map<String, String> getPermissions(WorkspaceIdentity wsi,
			WorkspaceUser user)
			throws NoSuchWorkspaceException, WorkspaceCommunicationException,
			CorruptWorkspaceDBException {
		Map<String, String> ret = new HashMap<String, String>(); 
		final WorkspaceIdentifier wksp = processWorkspaceIdentifier(wsi);
		final Map<User, Permission> acls = ws.getPermissions(
				user, wksp);
		for (User acl: acls.keySet()) {
			ret.put(acl.getUser(), translatePermission(acls.get(acl)));
		}
		return ret;
	}

	public List<Tuple11<Long, String, String, String, Long, String, Long, String, String, Long, Map<String, String>>> saveObjects(
			final SaveObjectsParams params, final WorkspaceUser user)
			throws ParseException, WorkspaceCommunicationException,
			WorkspaceAuthorizationException, NoSuchObjectException,
			CorruptWorkspaceDBException, NoSuchWorkspaceException,
			TypedObjectValidationException, TypeStorageException,
			BadJsonSchemaDocumentException, InstanceValidationException {

		checkAddlArgs(params.getAdditionalProperties(), params.getClass());
		final WorkspaceIdentifier wsi = processWorkspaceIdentifier(
				params.getWorkspace(), params.getId());
		final List<WorkspaceSaveObject> woc = new ArrayList<WorkspaceSaveObject>();
		int count = 1;
		if (params.getObjects().isEmpty()) {
			throw new IllegalArgumentException("No data provided");
		}
		for (ObjectSaveData d: params.getObjects()) {
			checkAddlArgs(d.getAdditionalProperties(), d.getClass());
			ObjectIDNoWSNoVer oi = null;
			if (d.getName() != null || d.getObjid() != null) {
				 oi = ObjectIDNoWSNoVer.create(d.getName(), d.getObjid());
			}
			String errprefix = "Object ";
			if (oi == null) {
				errprefix += count;
			} else {
				errprefix += count + ", " + oi.getIdentifierString() + ",";
			}
			if (d.getData() == null) {
				throw new IllegalArgumentException(errprefix + " has no data");
			}
			TypeDefId t;
			try {
				t = TypeDefId.fromTypeString(d.getType());
			} catch (IllegalArgumentException iae) {
				throw new IllegalArgumentException(errprefix + " type error: "
						+ iae.getLocalizedMessage(), iae);
			}
			final Provenance p = au.processProvenance(user,
					d.getProvenance());
			final boolean hidden = au.longToBoolean(d.getHidden());
			try {
				if (oi == null) {
					woc.add(new WorkspaceSaveObject(d.getData(),
							t, d.getMeta(), p, hidden));
				} else {
					woc.add(new WorkspaceSaveObject(oi,
							d.getData(), t, d.getMeta(), p,
							hidden));
				}
			} catch (IllegalArgumentException iae) {
				throw new IllegalArgumentException(errprefix + " save error: "
						+ iae.getLocalizedMessage(), iae);
			}
			count++;
		}
		params.setObjects(null); // garbage collect the objects, although
		// just passing a pointer around so no biggie
		// setting params = null won't help since the method caller still has a ref
		
		final List<ObjectInformation> meta = ws.saveObjects(user, wsi, woc); 
		return au.objInfoToTuple(meta);
	}
	
	public void grantModuleOwnership(final GrantModuleOwnershipParams params,
			final WorkspaceUser user, boolean asAdmin)
			throws TypeStorageException, NoSuchPrivilegeException {
		checkAddlArgs(params.getAdditionalProperties(),
				GrantModuleOwnershipParams.class);
		ws.grantModuleOwnership(params.getMod(), params.getNewOwner(),
				au.longToBoolean(params.getWithGrantOption()), user, asAdmin);
	}

	public void removeModuleOwnership(final RemoveModuleOwnershipParams params,
			final WorkspaceUser user, final boolean asAdmin)
			throws NoSuchPrivilegeException, TypeStorageException {
		checkAddlArgs(params.getAdditionalProperties(),
				RemoveModuleOwnershipParams.class);
		ws.removeModuleOwnership(params.getMod(), params.getOldOwner(),
				user, asAdmin);
	}

}
