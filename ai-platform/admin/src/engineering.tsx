import {useCallback,useEffect,useRef,useState,type ReactNode} from 'react';
import {Alert,Box,Button,Card,CardContent,Chip,LinearProgress,MenuItem,Stack,Table,TableBody,TableCell,TableHead,TableRow,TextField,Typography} from '@mui/material';
import {api,upload} from './api';
import type {ArchiveImport,EngApproval,EngProject,EngRun} from './types';

const PROJECT_TYPES=['auto','python','node','flutter','android','mixed'] as const;
const ACTIVE=new Set(['queued','running','waiting_approval']);

function Section({title,children,error}:{title:string;children:ReactNode;error?:string}){
  return <Card><CardContent><Typography variant="h6" gutterBottom>{title}</Typography>{error&&<Alert severity="error" sx={{mb:2}}>{error}</Alert>}{children}</CardContent></Card>;
}

function statusColor(status:string):'default'|'success'|'error'|'warning'|'info'{
  if(status==='completed')return 'success';
  if(status==='failed')return 'error';
  if(status==='waiting_approval')return 'warning';
  if(ACTIVE.has(status))return 'info';
  return 'default';
}

/** Pending command approvals across all users. A paused run cannot resume until every one is decided. */
export function Approvals(){
  const [items,setItems]=useState<EngApproval[]>([]);
  const [error,setError]=useState('');
  const [busy,setBusy]=useState<string|null>(null);
  const [note,setNote]=useState('');

  const load=useCallback(async()=>{
    try{const page=await api<{items:EngApproval[]}>('/admin/engineering/approvals?page=1&page_size=50');setItems(page.items);setError('')}
    catch(e){setError(e instanceof Error?e.message:'Failed to load approvals')}
  },[]);

  useEffect(()=>{void load();const t=setInterval(()=>{void load()},10000);return()=>clearInterval(t)},[load]);

  async function decide(id:string,decision:'approved'|'rejected'){
    setBusy(id);
    try{
      await api(`/admin/engineering/approvals/${id}/decision`,{method:'POST',body:JSON.stringify({decision,note:note||null})});
      setNote('');await load();
    }catch(e){setError(e instanceof Error?e.message:'Decision failed')}
    finally{setBusy(null)}
  }

  return <Section title="Pending command approvals" error={error}>
    {items.length===0
      ? <Typography color="text.secondary">No run is waiting for a command decision.</Typography>
      : <Stack spacing={2}>
          <TextField size="small" label="Decision note (optional)" value={note} onChange={e=>setNote(e.target.value)}/>
          <Table size="small">
            <TableHead><TableRow><TableCell>Requested</TableCell><TableCell>Agent</TableCell><TableCell>Reason</TableCell><TableCell align="right">Decision</TableCell></TableRow></TableHead>
            <TableBody>{items.map(a=>
              <TableRow key={a.id}>
                <TableCell>{new Date(a.created_at).toLocaleString()}</TableCell>
                <TableCell>{a.requested_by_agent??'—'}</TableCell>
                <TableCell sx={{maxWidth:520,whiteSpace:'pre-wrap'}}>{a.reason}</TableCell>
                <TableCell align="right">
                  <Stack direction="row" spacing={1} justifyContent="flex-end">
                    <Button size="small" variant="contained" disabled={busy===a.id} onClick={()=>void decide(a.id,'approved')}>Approve</Button>
                    <Button size="small" color="error" disabled={busy===a.id} onClick={()=>void decide(a.id,'rejected')}>Reject</Button>
                  </Stack>
                </TableCell>
              </TableRow>)}
            </TableBody>
          </Table>
        </Stack>}
  </Section>;
}

/** Create a project, import its source ZIP and drive runs from the console. */
export function Projects(){
  const [projects,setProjects]=useState<EngProject[]>([]);
  const [runs,setRuns]=useState<EngRun[]>([]);
  const [selected,setSelected]=useState<string>('');
  const [name,setName]=useState('');
  const [projectType,setProjectType]=useState<string>('auto');
  const [goal,setGoal]=useState('');
  const [error,setError]=useState('');
  const [message,setMessage]=useState('');
  const [busy,setBusy]=useState(false);
  const [uploading,setUploading]=useState(false);
  const fileRef=useRef<HTMLInputElement>(null);

  const loadProjects=useCallback(async()=>{
    try{const list=await api<EngProject[]>('/engineering/projects');setProjects(list);
      setSelected(current=>current||list[0]?.id||'');setError('')}
    catch(e){setError(e instanceof Error?e.message:'Failed to load projects')}
  },[]);

  const loadRuns=useCallback(async(projectId:string)=>{
    if(!projectId){setRuns([]);return}
    try{setRuns(await api<EngRun[]>(`/engineering/projects/${projectId}/runs`))}
    catch(e){setError(e instanceof Error?e.message:'Failed to load runs')}
  },[]);

  useEffect(()=>{void loadProjects()},[loadProjects]);
  useEffect(()=>{
    void loadRuns(selected);
    const t=setInterval(()=>{void loadRuns(selected)},5000);
    return()=>clearInterval(t);
  },[selected,loadRuns]);

  async function act<T>(fn:()=>Promise<T>,ok:string){
    setBusy(true);setError('');setMessage('');
    try{await fn();setMessage(ok)}
    catch(e){setError(e instanceof Error?e.message:'Request failed')}
    finally{setBusy(false)}
  }

  async function createProject(){
    if(!name.trim())return;
    await act(async()=>{
      const created=await api<EngProject>('/engineering/projects',{method:'POST',body:JSON.stringify({name:name.trim(),project_type:projectType,settings:{}})});
      setName('');await loadProjects();setSelected(created.id);
    },'Project created');
  }

  async function importArchive(file:File){
    if(!selected)return;
    setUploading(true);setError('');setMessage('');
    try{
      const result=await upload<ArchiveImport>(`/engineering/projects/${selected}/archive`,file);
      setMessage(`Imported ${result.files} files (${(result.bytes/1_000_000).toFixed(1)} MB)`);
    }catch(e){setError(e instanceof Error?e.message:'Archive import failed')}
    finally{setUploading(false);if(fileRef.current)fileRef.current.value=''}
  }

  async function createRun(){
    if(!selected||goal.trim().length<3)return;
    await act(async()=>{
      const run=await api<EngRun>(`/engineering/projects/${selected}/runs`,{method:'POST',body:JSON.stringify({goal:goal.trim()})});
      await api(`/engineering/runs/${run.id}/start`,{method:'POST'});
      setGoal('');await loadRuns(selected);
    },'Run queued');
  }

  return <Stack spacing={2}>
    <Section title="Projects" error={error}>
      <Stack spacing={2}>
        {message&&<Alert severity="success" onClose={()=>setMessage('')}>{message}</Alert>}
        <Stack direction={{xs:'column',sm:'row'}} spacing={2}>
          <TextField label="New project name" value={name} onChange={e=>setName(e.target.value)} size="small" sx={{flexGrow:1}}/>
          <TextField select label="Type" value={projectType} onChange={e=>setProjectType(e.target.value)} size="small" sx={{minWidth:140}}>
            {PROJECT_TYPES.map(t=><MenuItem key={t} value={t}>{t}</MenuItem>)}
          </TextField>
          <Button variant="contained" onClick={()=>void createProject()} disabled={busy||!name.trim()}>Create</Button>
        </Stack>
        <TextField select label="Active project" value={selected} onChange={e=>setSelected(e.target.value)} size="small" disabled={projects.length===0}>
          {projects.map(p=><MenuItem key={p.id} value={p.id}>{p.name} · {p.project_type}</MenuItem>)}
        </TextField>
        <Box>
          <input ref={fileRef} type="file" accept=".zip,application/zip" style={{display:'none'}}
                 onChange={e=>{const f=e.target.files?.[0];if(f)void importArchive(f)}}/>
          <Button variant="outlined" disabled={!selected||uploading} onClick={()=>fileRef.current?.click()}>
            {uploading?'Importing…':'Import source ZIP'}
          </Button>
          {uploading&&<LinearProgress sx={{mt:1}}/>}
          <Typography variant="caption" display="block" sx={{mt:1}} color="text.secondary">
            Archives may not contain <code>.git</code>, <code>.ai-platform</code> or symlinks. The size ceiling is the server&apos;s ENGINEERING_MAX_ARCHIVE_BYTES.
          </Typography>
        </Box>
      </Stack>
    </Section>

    <Section title="Runs">
      <Stack spacing={2}>
        <Stack direction={{xs:'column',sm:'row'}} spacing={2}>
          <TextField label="Run goal" value={goal} onChange={e=>setGoal(e.target.value)} size="small" sx={{flexGrow:1}} multiline maxRows={4}/>
          <Button variant="contained" onClick={()=>void createRun()} disabled={busy||!selected||goal.trim().length<3}>Start run</Button>
        </Stack>
        {runs.length===0
          ? <Typography color="text.secondary">No runs for this project yet.</Typography>
          : <Table size="small">
              <TableHead><TableRow><TableCell>Goal</TableCell><TableCell>Status</TableCell><TableCell>Stage</TableCell><TableCell>Progress</TableCell><TableCell align="right">Action</TableCell></TableRow></TableHead>
              <TableBody>{runs.map(r=>
                <TableRow key={r.id}>
                  <TableCell sx={{maxWidth:380}}>{r.goal.slice(0,160)}</TableCell>
                  <TableCell><Chip size="small" label={r.status} color={statusColor(r.status)}/></TableCell>
                  <TableCell>{r.stage}</TableCell>
                  <TableCell>{r.progress}%</TableCell>
                  <TableCell align="right">
                    {ACTIVE.has(r.status)&&<Button size="small" color="error"
                      onClick={()=>void act(()=>api(`/engineering/runs/${r.id}/cancel`,{method:'POST'}),'Cancellation requested').then(()=>loadRuns(selected))}>Cancel</Button>}
                  </TableCell>
                </TableRow>)}
              </TableBody>
            </Table>}
      </Stack>
    </Section>
  </Stack>;
}
