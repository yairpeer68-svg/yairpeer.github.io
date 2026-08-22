export type TokenPair={access_token:string;refresh_token:string;expires_in:number};
export type User={id:string;email:string;display_name:string|null;is_admin:boolean;is_active:boolean;email_verified_at:string|null;created_at:string};
export type Page<T>={items:T[];page:number;page_size:number;total:number};
export type ApiError={error?:{code?:string;message?:string;request_id?:string}};
export type EngProject={id:string;name:string;description:string|null;project_type:string;status:string;created_at:string;updated_at:string};
export type EngRun={id:string;project_id:string;goal:string;status:string;stage:string;progress:number;repair_attempts:number;error:string|null;created_at:string;started_at:string|null;finished_at:string|null};
export type EngApproval={id:string;run_id:string;task_id:string|null;kind:string;reason:string;status:string;requested_by_agent:string|null;created_at:string;decided_at:string|null};
export type ArchiveImport={files:number;bytes:number;manifest_hash:string;code_index:string};
