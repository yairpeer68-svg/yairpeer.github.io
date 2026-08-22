import {StrictMode} from 'react';
import {createRoot} from 'react-dom/client';
import {CssBaseline,ThemeProvider,createTheme} from '@mui/material';
import {AuthProvider} from './auth';
import App from './App';
const theme=createTheme({cssVariables:true,colorSchemes:{light:true,dark:true},typography:{fontFamily:'Inter, system-ui, sans-serif'}});
createRoot(document.getElementById('root')!).render(<StrictMode><ThemeProvider theme={theme}><CssBaseline/><AuthProvider><App/></AuthProvider></ThemeProvider></StrictMode>);
